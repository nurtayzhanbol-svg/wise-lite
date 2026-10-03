package com.wiselite.transfer.risk;

import com.wiselite.events.RiskDecision;
import com.wiselite.transfer.risk.RiskDecisionLog.Entry;
import com.wiselite.transfer.risk.RiskDecisionLog.Source;
import com.wiselite.transfer.transfer.TransferNotFoundException;
import com.wiselite.transfer.transfer.TransferRepository;
import com.wiselite.transfer.transfer.TransferService;
import com.wiselite.transfer.transfer.TransferState;
import io.micrometer.core.instrument.Metrics;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Applies automated risk decisions. One transaction: dedupe row, audit row, state change, ledger
 * entries (BLOCK refunds) and the outbox event. A crash anywhere before commit leaves no trace, and the
 * redelivered record is processed as if for the first time.
 *
 * <p>A decision only acts on a transfer that is still FUNDED. Anything else (already decided, timed out
 * and HELD, terminal) means the decision is late or conflicting: it is recorded as IGNORED and changes
 * nothing. That is what makes duplicate, reordered and late decisions safe without per-transfer sequence numbers.
 */
@Service
public class RiskDecisionHandler {

    public enum Outcome { APPLIED, DUPLICATE, IGNORED }

    private final JdbcClient jdbc;
    private final TransferRepository repository;
    private final TransferService transfers;
    private final RiskDecisionLog log;

    public RiskDecisionHandler(JdbcClient jdbc, TransferRepository repository, TransferService transfers, RiskDecisionLog log) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.transfers = transfers;
        this.log = log;
    }

    @Transactional
    public Outcome handle(RiskDecision d) {
        int fresh = jdbc.sql("INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING")
                .param(d.decisionId()).update();
        if (fresh == 0) {
            return count(d, Outcome.DUPLICATE);
        }
        // Row lock first: serialises us against the timeout sweeper and operators acting on the same transfer.
        var current = repository.lock(d.transferId()).orElseThrow(() -> new TransferNotFoundException(d.transferId()));
        var reasons = d.reasons() == null ? "" : String.join(",", d.reasons());
        boolean applies = current.state() == TransferState.FUNDED;
        log.record(d.transferId(), new Entry(d.decisionId(), Source.ENGINE, d.decision(), reasons, applies, current.state(),
                "risk-engine " + d.rulesVersion(), d.decidedAt()));
        if (!applies) {
            return count(d, Outcome.IGNORED);
        }
        switch (d.decision()) {
            case RiskDecision.ALLOW -> transfers.approve(d.transferId(), "risk ALLOW");
            case RiskDecision.REVIEW -> transfers.hold(d.transferId(), "risk REVIEW: " + reasons);
            case RiskDecision.BLOCK -> transfers.fail(d.transferId(), "risk BLOCK: " + reasons);
            default -> throw new IllegalArgumentException("Unknown risk decision " + d.decision());
        }
        return count(d, Outcome.APPLIED);
    }

    private static Outcome count(RiskDecision d, Outcome outcome) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Metrics.counter("wiselite.risk.decisions", "decision", d.decision(), "outcome", outcome.name()).increment();
                }
            });
        }
        return outcome;
    }
}
