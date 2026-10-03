package com.wiselite.transfer.risk;

import com.wiselite.events.RiskDecision;
import com.wiselite.transfer.risk.RiskDecisionLog.Entry;
import com.wiselite.transfer.risk.RiskDecisionLog.Source;
import com.wiselite.transfer.transfer.TransferRepository;
import com.wiselite.transfer.transfer.TransferService;
import com.wiselite.transfer.transfer.TransferState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fail closed: a transfer without a risk decision after {@code timeout} is HELD for a human. Never
 * auto-approved (risk-engine down must not mean "no risk checks"), never auto-refunded (a slow engine
 * must not cancel legitimate transfers). A decision that arrives later is recorded but does not apply.
 */
@Service
public class RiskTimeoutService {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TransferRepository repository;
    private final TransferService transfers;
    private final RiskDecisionLog log;
    private final Clock clock;

    public RiskTimeoutService(JdbcClient jdbc, TransactionTemplate tx, TransferRepository repository, TransferService transfers,
            RiskDecisionLog log, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.repository = repository;
        this.transfers = transfers;
        this.log = log;
        this.clock = clock;
    }

    /** Holds up to 100 overdue transfers, each in its own transaction. Returns how many were held. */
    public int holdOverdue(Duration timeout) {
        var overdue = jdbc.sql("""
                        SELECT id FROM transfers
                        WHERE state = 'FUNDED' AND updated_at < now() - (? * interval '1 millisecond')
                        ORDER BY updated_at LIMIT 100""")
                .param(timeout.toMillis())
                .query(UUID.class).list();
        int held = 0;
        for (var id : overdue) {
            if (Boolean.TRUE.equals(tx.execute(s -> holdIfStillWaiting(id, timeout)))) {
                held++;
            }
        }
        return held;
    }

    private boolean holdIfStillWaiting(UUID id, Duration timeout) {
        var current = repository.lock(id).orElseThrow();
        if (current.state() != TransferState.FUNDED) {
            return false; // the decision won the race
        }
        var reason = "RISK_TIMEOUT: no decision within " + timeout;
        log.record(id, new Entry(UUID.randomUUID(), Source.TIMEOUT, RiskDecision.REVIEW, reason, true, current.state(),
                "timeout-sweeper", Instant.now(clock)));
        transfers.hold(id, reason);
        return true;
    }
}
