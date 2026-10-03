package com.wiselite.recon;

import com.wiselite.recon.Reconciler.LedgerTransfer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads transfers and runs the ledger self-checks inside ONE repeatable-read transaction:
 * every query sees the same MVCC snapshot, so a transfer committing mid-run can't make
 * the clearing balance and the in-flight total disagree spuriously.
 */
@Component
public class TransferSource {

    private record Clearing(String currency, long balance, long inFlight) {}

    public record Snapshot(List<LedgerTransfer> transfers, List<Break> ledgerBreaks) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate snapshot;

    public TransferSource(@Qualifier("transfersDataSource") DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
    }

    public Snapshot load() {
        return snapshot.execute(status -> new Snapshot(transfers(), ledgerChecks()));
    }

    private List<LedgerTransfer> transfers() {
        return jdbc.sql("SELECT id, amount_minor, currency, state, updated_at FROM transfers")
                .query((rs, n) -> new LedgerTransfer(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3).trim(),
                        rs.getString(4), rs.getTimestamp(5).toInstant()))
                .list();
    }

    private List<Break> ledgerChecks() {
        var breaks = new ArrayList<Break>();
        jdbc.sql("SELECT currency, SUM(amount_minor) FROM ledger_entries GROUP BY currency HAVING SUM(amount_minor) <> 0")
                .query((rs, n) -> Break.of(BreakType.TRIAL_BALANCE_NONZERO, rs.getString(1).trim(),
                        "entries sum to " + rs.getLong(2)))
                .list().forEach(breaks::add);
        jdbc.sql("""
                        SELECT b.account_id, b.balance_minor, COALESCE(SUM(e.amount_minor), 0)
                        FROM account_balances b LEFT JOIN ledger_entries e ON e.account_id = b.account_id
                        GROUP BY b.account_id, b.balance_minor
                        HAVING b.balance_minor <> COALESCE(SUM(e.amount_minor), 0)""")
                .query((rs, n) -> Break.of(BreakType.PROJECTION_DRIFT, rs.getString(1),
                        "projection " + rs.getLong(2) + ", entries " + rs.getLong(3)))
                .list().forEach(breaks::add);
        jdbc.sql("""
                        SELECT a.currency,
                               COALESCE((SELECT SUM(e.amount_minor) FROM ledger_entries e WHERE e.account_id = a.id), 0),
                               COALESCE((SELECT SUM(t.amount_minor) FROM transfers t
                                         WHERE t.currency = a.currency AND t.state IN ('FUNDED', 'HELD', 'APPROVED', 'PROCESSING')), 0)
                        FROM accounts a WHERE a.type = 'PAYOUT_CLEARING'""")
                .query((rs, n) -> new Clearing(rs.getString(1).trim(), rs.getLong(2), rs.getLong(3)))
                .list().stream()
                .filter(c -> c.balance() != c.inFlight())
                .map(c -> Break.of(BreakType.CLEARING_MISMATCH, c.currency(), "clearing " + c.balance() + ", in flight " + c.inFlight()))
                .forEach(breaks::add);
        return breaks;
    }
}
