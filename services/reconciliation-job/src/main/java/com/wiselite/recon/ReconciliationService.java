package com.wiselite.recon;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {

    public record Report(UUID runId, Instant cutoff, int transfersChecked, int railLinesChecked, List<Break> breaks) {}

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final TransferSource transfers;
    private final PayoutSource payouts;
    private final RailStatementClient rail;
    private final JdbcClient jdbc;
    private final ReconProperties properties;
    private final Clock clock;

    public ReconciliationService(TransferSource transfers, PayoutSource payouts, RailStatementClient rail, JdbcClient jdbc,
            ReconProperties properties, Clock clock) {
        this.transfers = transfers;
        this.payouts = payouts;
        this.rail = rail;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    /** Read-only towards every source; the only writes are the report rows. */
    @Transactional
    public Report run() {
        var cutoff = Instant.now(clock);
        // Ledger first, rail last: the rail can only be "ahead" of us, never behind (see ADR 0014).
        var snapshot = transfers.load();
        var payoutRecords = payouts.load();
        var statement = rail.statement();

        var breaks = new ArrayList<>(snapshot.ledgerBreaks());
        breaks.addAll(Reconciler.reconcile(snapshot.transfers(), payoutRecords, statement, cutoff, properties.grace()));
        breaks.sort(Comparator.comparing(Break::severity).thenComparing(Break::type));

        var runId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO recon_runs (id, cutoff, started_at, finished_at, transfers_checked, rail_lines_checked, breaks)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(runId, java.sql.Timestamp.from(cutoff), java.sql.Timestamp.from(cutoff),
                        java.sql.Timestamp.from(Instant.now(clock)), snapshot.transfers().size(), statement.size(), breaks.size())
                .update();
        for (var b : breaks) {
            jdbc.sql("INSERT INTO recon_breaks (run_id, type, severity, reference, details) VALUES (?, ?, ?, ?, ?)")
                    .params(runId, b.type().name(), b.severity().name(), b.reference(), b.details())
                    .update();
        }
        if (!breaks.isEmpty()) {
            log.warn("Reconciliation {} found {} breaks, worst {}", runId, breaks.size(), breaks.get(0));
        }
        return new Report(runId, cutoff, snapshot.transfers().size(), statement.size(), breaks);
    }

    public List<Break> breaks(UUID runId) {
        return jdbc.sql("SELECT type, severity, reference, details FROM recon_breaks WHERE run_id = ? ORDER BY id")
                .param(runId)
                .query((rs, n) -> new Break(BreakType.valueOf(rs.getString(1)), BreakType.Severity.valueOf(rs.getString(2)),
                        rs.getString(3), rs.getString(4)))
                .list();
    }
}
