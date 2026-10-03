package com.wiselite.payout;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.wiselite.events.PayoutStatusChanged;
import com.wiselite.events.Topics;
import com.wiselite.outbox.OutboxWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

/**
 * All payout state changes. Every transition is a single conditional UPDATE guarded by the
 * current status, so a late HTTP response can never overwrite a webhook that already settled
 * the payout (and vice versa).
 */
@Repository
public class PayoutStore {

    public enum SettleOutcome { APPLIED, ALREADY_IN_THAT_STATE, CONFLICT, NOT_FOUND }

    public record ClaimedPayout(UUID transferId, long amountMinor, String currency, String recipientName, String recipientIban,
            int attempts) {}

    private static final String NOT_FINAL = "('PENDING', 'SUBMITTED', 'UNKNOWN')";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final OutboxWriter outbox;

    public PayoutStore(JdbcClient jdbc, Clock clock, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.outbox = outbox;
    }

    /**
     * Leases due payouts. The lease (not a held DB lock) protects the payout during the HTTP call:
     * we never keep a transaction open across a network call to a third party.
     */
    public List<ClaimedPayout> claimDue(int limit, Duration lease) {
        var now = Instant.now(clock);
        return jdbc.sql("""
                        UPDATE payouts p SET lease_until = :leaseUntil, attempts = p.attempts + 1, updated_at = :now
                        WHERE p.transfer_id IN (
                            SELECT transfer_id FROM payouts
                            WHERE status IN %s AND next_attempt_at <= :now AND (lease_until IS NULL OR lease_until < :now)
                            ORDER BY next_attempt_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING p.transfer_id, p.amount_minor, p.currency, p.recipient_name, p.recipient_iban, p.attempts
                        """.formatted(NOT_FINAL))
                .param("leaseUntil", Timestamp.from(now.plus(lease)))
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query((rs, n) -> new ClaimedPayout(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3).trim(),
                        rs.getString(4), rs.getString(5), rs.getInt(6)))
                .list();
    }

    /** Hand a claimed payout back untouched (e.g. circuit open): the attempt didn't happen. */
    public void release(UUID transferId) {
        jdbc.sql("UPDATE payouts SET lease_until = NULL, attempts = attempts - 1 WHERE transfer_id = ? AND status IN " + NOT_FINAL)
                .param(transferId).update();
    }

    public void markSubmitted(UUID transferId, String railsPaymentId, Instant checkAgainAt) {
        jdbc.sql("""
                        UPDATE payouts SET status = 'SUBMITTED', rails_payment_id = ?, next_attempt_at = ?, lease_until = NULL,
                                           last_error = NULL, updated_at = ?
                        WHERE transfer_id = ? AND status IN %s""".formatted(NOT_FINAL))
                .params(railsPaymentId, Timestamp.from(checkAgainAt), Timestamp.from(Instant.now(clock)), transferId)
                .update();
    }

    public void markUnknown(UUID transferId, String error, Instant retryAt) {
        jdbc.sql("""
                        UPDATE payouts SET status = 'UNKNOWN', last_error = ?, next_attempt_at = ?, lease_until = NULL, updated_at = ?
                        WHERE transfer_id = ? AND status IN %s""".formatted(NOT_FINAL))
                .params(error, Timestamp.from(retryAt), Timestamp.from(Instant.now(clock)), transferId)
                .update();
    }

    public void markManualReview(UUID transferId, String reason) {
        jdbc.sql("""
                        UPDATE payouts SET status = 'MANUAL_REVIEW', last_error = ?, lease_until = NULL, updated_at = ?
                        WHERE transfer_id = ? AND status IN %s""".formatted(NOT_FINAL))
                .params(reason, Timestamp.from(Instant.now(clock)), transferId)
                .update();
    }

    /**
     * Final outcome, from a webhook or a re-submit response. MANUAL_REVIEW can still be resolved.
     * Only the transition that actually applies emits a {@link PayoutStatusChanged} (outbox, same tx).
     */
    @Transactional
    public SettleOutcome settle(UUID transferId, PayoutStatus finalStatus, String note) {
        int rows = jdbc.sql("""
                        UPDATE payouts SET status = ?, last_error = COALESCE(?, last_error), lease_until = NULL, updated_at = ?
                        WHERE transfer_id = ? AND status IN ('PENDING', 'SUBMITTED', 'UNKNOWN', 'MANUAL_REVIEW')""")
                .params(finalStatus.name(), note, Timestamp.from(Instant.now(clock)), transferId)
                .update();
        if (rows == 1) {
            var event = new PayoutStatusChanged(UUID.randomUUID(), transferId, finalStatus.name(), note, Instant.now(clock));
            outbox.append(Topics.PAYOUT_EVENTS, transferId.toString(), PayoutStatusChanged.TYPE, event.eventId(), event);
            return SettleOutcome.APPLIED;
        }
        var current = status(transferId);
        if (current.isEmpty()) {
            return SettleOutcome.NOT_FOUND;
        }
        if (current.get() == finalStatus) {
            return SettleOutcome.ALREADY_IN_THAT_STATE;
        }
        jdbc.sql("UPDATE payouts SET last_error = ? WHERE transfer_id = ?")
                .params("conflicting outcome " + finalStatus + " after " + current.get(), transferId).update();
        return SettleOutcome.CONFLICT;
    }

    public Optional<PayoutStatus> status(UUID transferId) {
        return jdbc.sql("SELECT status FROM payouts WHERE transfer_id = ?").param(transferId)
                .query(String.class).optional().map(PayoutStatus::valueOf);
    }
}
