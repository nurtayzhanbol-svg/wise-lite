package com.wiselite.recon;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real schemas: the transfers and payouts databases are migrated with the owning services'
 * own Flyway scripts (read from their source folders), so this test breaks if they drift.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "wiselite.recon.scheduler.enabled=false", "wiselite.recon.grace=PT0S"})
class ReconciliationIT {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static final HttpServer RAIL;
    static volatile List<String> statement = new ArrayList<>();
    static DriverManagerDataSource transfersDb;
    static DriverManagerDataSource payoutsDb;

    static {
        PG.start();
        try (var c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
                var st = c.createStatement()) {
            st.execute("CREATE DATABASE transfers");
            st.execute("CREATE DATABASE payouts");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
        transfersDb = new DriverManagerDataSource(url("transfers"), PG.getUsername(), PG.getPassword());
        payoutsDb = new DriverManagerDataSource(url("payouts"), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(transfersDb).locations("filesystem:../transfer-service/src/main/resources/db/migration")
                .load().migrate();
        Flyway.configure().dataSource(payoutsDb).locations("filesystem:../payout-worker/src/main/resources/db/migration")
                .load().migrate();
        try {
            RAIL = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            RAIL.createContext("/statement", ex -> {
                var body = ("[" + String.join(",", statement) + "]").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            RAIL.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static String url(String db) {
        return PG.getJdbcUrl().replaceFirst("/" + PG.getDatabaseName() + "(\\?|$)", "/" + db + "$1");
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        for (var db : List.of("recon", "transfers", "payouts")) {
            var name = db.equals("recon") ? PG.getDatabaseName() : db;
            r.add("wiselite.recon." + db + "-db.url", () -> url(name));
            r.add("wiselite.recon." + db + "-db.username", PG::getUsername);
            r.add("wiselite.recon." + db + "-db.password", PG::getPassword);
        }
        r.add("wiselite.recon.rails-url", () -> "http://localhost:" + RAIL.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        RAIL.stop(0);
    }

    @Autowired ReconciliationService recon;
    @Autowired TestRestTemplate http;

    private final JdbcClient ledgerDb = JdbcClient.create(transfersDb);
    private final JdbcClient payoutDb = JdbcClient.create(payoutsDb);
    private final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(transfersDb));
    private UUID customer;
    private UUID clearing;
    private UUID funding;

    @BeforeEach
    void seed() {
        // TRUNCATE doesn't fire the append-only row triggers; fine for test setup.
        ledgerDb.sql("TRUNCATE ledger_entries, journal_entries, account_balances, transfer_state_history, transfers, accounts CASCADE")
                .update();
        payoutDb.sql("TRUNCATE payouts").update();
        statement = new ArrayList<>();
        customer = account("CUSTOMER", UUID.randomUUID(), false);
        clearing = account("PAYOUT_CLEARING", null, false);
        funding = account("EXTERNAL_FUNDING", null, true);
        post("topup", funding, customer, 100_000);
    }

    @Test
    void cleanBooksReconcileWithNoBreaks() {
        var done = completedTransfer(10_000);
        var inFlight = fundedTransfer(5_000);
        payout(done, "SETTLED");
        payout(inFlight, "SUBMITTED");
        rail(done, 10_000, "SETTLED");
        rail(inFlight, 5_000, "PENDING");

        var report = http.postForObject("/reconciliation/runs", null, ReconciliationService.Report.class);

        assertThat(report.breaks()).isEmpty();
        assertThat(report.transfersChecked()).isEqualTo(2);
        assertThat(report.railLinesChecked()).isEqualTo(2);
    }

    @Test
    void detectsDisagreementsWithTheRail() {
        var refunded = fundedTransfer(3_000);
        post("refund-" + refunded, clearing, customer, 3_000);
        ledgerDb.sql("UPDATE transfers SET state = 'REFUNDED' WHERE id = ?").param(refunded).update();
        rail(refunded, 3_000, "SETTLED"); // ...but the recipient was paid anyway
        var done = completedTransfer(10_000);
        rail(done, 9_999, "SETTLED");
        rail(UUID.randomUUID(), 777, "SETTLED");

        var report = recon.run();

        assertThat(report.breaks()).extracting(Break::type).containsExactlyInAnyOrder(
                BreakType.PAID_BUT_REFUNDED, BreakType.AMOUNT_MISMATCH, BreakType.UNKNOWN_AT_RAIL);
        assertThat(recon.breaks(report.runId())).hasSize(3);
    }

    @Test
    void detectsLedgerCorruptionThatSlippedPastTheDatabaseGuards() {
        completedTransfer(10_000);
        // A transfer marked FUNDED without the matching ledger movement:
        ledgerDb.sql("""
                        INSERT INTO transfers (id, owner_id, source_account_id, amount_minor, currency, recipient_name, recipient_iban, state)
                        VALUES (?, ?, ?, 500, 'EUR', 'Bob', 'DE89370400440532013000', 'FUNDED')""")
                .params(UUID.randomUUID(), UUID.randomUUID(), customer).update();
        // A projection updated without an entry (e.g. a buggy hand-written fix):
        ledgerDb.sql("UPDATE account_balances SET balance_minor = balance_minor + 1 WHERE account_id = ?").param(customer).update();
        // An unbalanced entry, possible only because someone disabled the guard trigger:
        ledgerDb.sql("ALTER TABLE ledger_entries DISABLE TRIGGER ledger_entries_balanced").update();
        try {
            var journal = UUID.randomUUID();
            ledgerDb.sql("INSERT INTO journal_entries (id, type, reference) VALUES (?, 'TRANSFER', 'oops')").param(journal).update();
            ledgerDb.sql("INSERT INTO ledger_entries (journal_entry_id, account_id, currency, amount_minor) VALUES (?, ?, 'EUR', 42)")
                    .params(journal, funding).update();
        } finally {
            ledgerDb.sql("ALTER TABLE ledger_entries ENABLE TRIGGER ledger_entries_balanced").update();
        }

        var types = recon.run().breaks().stream().map(Break::type).toList();

        assertThat(types).contains(BreakType.TRIAL_BALANCE_NONZERO, BreakType.PROJECTION_DRIFT, BreakType.CLEARING_MISMATCH,
                BreakType.MISSING_PAYOUT);
    }

    @Test
    void stuckTransferIsReportedWithTheRailsOutcome() {
        var t = fundedTransfer(2_000);
        payout(t, "MANUAL_REVIEW");
        rail(t, 2_000, "SETTLED");

        assertThat(recon.run().breaks()).singleElement().satisfies(b -> {
            assertThat(b.type()).isEqualTo(BreakType.STUCK_RESOLVABLE);
            assertThat(b.details()).contains("MANUAL_REVIEW", "SETTLED");
        });
    }

    private UUID account(String type, UUID owner, boolean allowNegative) {
        var id = UUID.randomUUID();
        ledgerDb.sql("INSERT INTO accounts (id, owner_id, currency, type) VALUES (?, ?, 'EUR', ?)").params(id, owner, type).update();
        ledgerDb.sql("INSERT INTO account_balances (account_id, currency, balance_minor, allow_negative) VALUES (?, 'EUR', 0, ?)")
                .params(id, allowNegative).update();
        return id;
    }

    /** One balanced journal entry plus projection update, in one transaction (as LedgerService does). */
    private void post(String reference, UUID from, UUID to, long amount) {
        tx.executeWithoutResult(s -> {
            var journal = UUID.randomUUID();
            ledgerDb.sql("INSERT INTO journal_entries (id, type, reference) VALUES (?, 'TRANSFER', ?)").params(journal, reference).update();
            for (var leg : List.of(new Object[] {from, -amount}, new Object[] {to, amount})) {
                ledgerDb.sql("INSERT INTO ledger_entries (journal_entry_id, account_id, currency, amount_minor) VALUES (?, ?, 'EUR', ?)")
                        .params(journal, leg[0], leg[1]).update();
                ledgerDb.sql("UPDATE account_balances SET balance_minor = balance_minor + ? WHERE account_id = ?")
                        .params(leg[1], leg[0]).update();
            }
        });
    }

    private UUID fundedTransfer(long amount) {
        var id = UUID.randomUUID();
        ledgerDb.sql("""
                        INSERT INTO transfers (id, owner_id, source_account_id, amount_minor, currency, recipient_name, recipient_iban, state)
                        VALUES (?, ?, ?, ?, 'EUR', 'Bob', 'DE89370400440532013000', 'FUNDED')""")
                .params(id, UUID.randomUUID(), customer, amount).update();
        post("fund-" + id, customer, clearing, amount);
        return id;
    }

    private UUID completedTransfer(long amount) {
        var id = fundedTransfer(amount);
        post("payout-" + id, clearing, funding, amount);
        ledgerDb.sql("UPDATE transfers SET state = 'COMPLETED' WHERE id = ?").param(id).update();
        return id;
    }

    private void payout(UUID transferId, String status) {
        payoutDb.sql("""
                        INSERT INTO payouts (transfer_id, amount_minor, currency, recipient_name, recipient_iban, status)
                        VALUES (?, 1, 'EUR', 'Bob', 'DE89370400440532013000', ?)""")
                .params(transferId, status).update();
    }

    private void rail(UUID reference, long amount, String status) {
        statement.add("""
                {"paymentId":"pay-%s","reference":"%s","amountMinor":%d,"currency":"EUR","status":"%s",\
                "createdAt":"2026-01-01T00:00:00Z"}""".formatted(reference, reference, amount, status));
    }
}
