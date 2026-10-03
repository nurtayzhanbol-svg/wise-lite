package com.wiselite.payout;

import com.wiselite.rails.api.RailsApi;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CallbackService {

    public enum Outcome { APPLIED, DUPLICATE, ALREADY_IN_THAT_STATE, CONFLICT }

    public static class PayoutNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PayoutNotFoundException(String reference) {
            super("No payout for reference " + reference);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(CallbackService.class);

    private final JdbcClient jdbc;
    private final PayoutStore store;

    public CallbackService(JdbcClient jdbc, PayoutStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    /** Webhooks are at-least-once too: dedupe by eventId in the same transaction as the effect. */
    @Transactional
    public Outcome handle(RailsApi.Callback callback) {
        var status = switch (String.valueOf(callback.status())) {
            case RailsApi.SETTLED -> PayoutStatus.SETTLED;
            case RailsApi.REJECTED -> PayoutStatus.REJECTED;
            default -> throw new IllegalArgumentException("Callback status must be final, got " + callback.status());
        };
        UUID eventId;
        UUID transferId;
        try {
            eventId = UUID.fromString(callback.eventId());
            transferId = UUID.fromString(callback.reference());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("eventId and reference must be UUIDs", e);
        }

        int fresh = jdbc.sql("INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING").param(eventId).update();
        if (fresh == 0) {
            return Outcome.DUPLICATE;
        }
        return switch (store.settle(transferId, status, null)) {
            case APPLIED -> Outcome.APPLIED;
            case ALREADY_IN_THAT_STATE -> Outcome.ALREADY_IN_THAT_STATE;
            case CONFLICT -> {
                // Accept (stop the rail retrying) but never flip a final state; a human resolves it.
                log.error("Conflicting rail outcome {} for payout {}", status, transferId);
                yield Outcome.CONFLICT;
            }
            case NOT_FOUND -> throw new PayoutNotFoundException(callback.reference()); // rolls back the dedupe row
        };
    }
}
