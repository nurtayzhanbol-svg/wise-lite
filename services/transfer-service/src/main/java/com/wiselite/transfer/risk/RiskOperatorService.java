package com.wiselite.transfer.risk;

import com.wiselite.events.RiskDecision;
import com.wiselite.transfer.risk.RiskDecisionLog.Entry;
import com.wiselite.transfer.risk.RiskDecisionLog.Source;
import com.wiselite.transfer.transfer.IllegalStateTransitionException;
import com.wiselite.transfer.transfer.Transfer;
import com.wiselite.transfer.transfer.TransferNotFoundException;
import com.wiselite.transfer.transfer.TransferRepository;
import com.wiselite.transfer.transfer.TransferService;
import com.wiselite.transfer.transfer.TransferState;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual verdicts on HELD transfers. Idempotent by state, under the transfer row lock:
 * <ul>
 *   <li>release on HELD approves; release again (already released) returns the transfer unchanged;
 *   <li>reject on HELD refunds; reject again (already rejected) returns the transfer unchanged;
 *   <li>the other verdict after one was applied, or any verdict on a transfer that isn't HELD → 409.
 * </ul>
 * So N concurrent releases give one APPROVED transition, one outbox event, at most one payout; release
 * racing reject has exactly one winner.
 */
@Service
public class RiskOperatorService {

    private final TransferRepository repository;
    private final TransferService transfers;
    private final RiskDecisionLog log;
    private final Clock clock;

    public RiskOperatorService(TransferRepository repository, TransferService transfers, RiskDecisionLog log, Clock clock) {
        this.repository = repository;
        this.transfers = transfers;
        this.log = log;
        this.clock = clock;
    }

    @Transactional
    public Transfer release(UUID id, String operator, String reason) {
        return decide(id, operator, reason, RiskDecision.ALLOW, TransferState.APPROVED);
    }

    @Transactional
    public Transfer reject(UUID id, String operator, String reason) {
        return decide(id, operator, reason, RiskDecision.BLOCK, TransferState.FAILED);
    }

    private Transfer decide(UUID id, String operator, String reason, String decision, TransferState target) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator is required");
        }
        var current = repository.lock(id).orElseThrow(() -> new TransferNotFoundException(id));
        if (current.state() != TransferState.HELD) {
            if (log.hasApplied(id, Source.OPERATOR, decision)) {
                return current; // the same verdict was already applied: a retry
            }
            throw new IllegalStateTransitionException(id, current.state(), target);
        }
        log.record(id, new Entry(UUID.randomUUID(), Source.OPERATOR, decision, reason, true, current.state(), operator,
                Instant.now(clock)));
        var why = (decision.equals(RiskDecision.ALLOW) ? "released by " : "rejected by ") + operator
                + (reason == null ? "" : ": " + reason);
        return decision.equals(RiskDecision.ALLOW) ? transfers.approve(id, why) : transfers.fail(id, why);
    }
}
