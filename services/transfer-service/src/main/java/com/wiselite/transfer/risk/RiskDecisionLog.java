package com.wiselite.transfer.risk;

import com.wiselite.transfer.transfer.TransferState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Append-only audit of risk decisions (table {@code risk_decisions}). Callers hold the transfer row lock. */
@Repository
public class RiskDecisionLog {

    public enum Source { ENGINE, TIMEOUT, OPERATOR }

    public record Entry(UUID decisionId, Source source, String decision, String reasons, boolean applied,
            TransferState stateBefore, String actor, Instant decidedAt) {}

    private final JdbcClient jdbc;

    public RiskDecisionLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID transferId, Entry e) {
        jdbc.sql("""
                        INSERT INTO risk_decisions (transfer_id, decision_id, source, decision, reasons, outcome, state_before, actor, decided_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (decision_id) DO NOTHING""")
                .params(transferId, e.decisionId(), e.source().name(), e.decision(), e.reasons(),
                        e.applied() ? "APPLIED" : "IGNORED", e.stateBefore().name(), e.actor(),
                        java.sql.Timestamp.from(e.decidedAt()))
                .update();
    }

    public boolean hasApplied(UUID transferId, Source source, String decision) {
        return jdbc.sql("""
                        SELECT count(*) FROM risk_decisions
                        WHERE transfer_id = ? AND source = ? AND decision = ? AND outcome = 'APPLIED'""")
                .params(transferId, source.name(), decision)
                .query(Long.class).single() > 0;
    }

    public List<Entry> list(UUID transferId) {
        return jdbc.sql("""
                        SELECT decision_id, source, decision, reasons, outcome, state_before, actor, decided_at
                        FROM risk_decisions WHERE transfer_id = ? ORDER BY id""")
                .param(transferId)
                .query((rs, n) -> new Entry(rs.getObject(1, UUID.class), Source.valueOf(rs.getString(2)), rs.getString(3),
                        rs.getString(4), "APPLIED".equals(rs.getString(5)), TransferState.valueOf(rs.getString(6)),
                        rs.getString(7), rs.getTimestamp(8).toInstant()))
                .list();
    }
}
