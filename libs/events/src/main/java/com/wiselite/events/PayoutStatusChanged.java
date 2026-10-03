package com.wiselite.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by payout-worker on {@link Topics#PAYOUT_EVENTS} when a payout reaches a final
 * status (SETTLED or REJECTED). Key: {@code transferId}. At-least-once: dedupe by {@code eventId}.
 */
public record PayoutStatusChanged(UUID eventId, UUID transferId, String status, String reason, Instant occurredAt) {

    public static final String TYPE = "PayoutStatusChanged";
    public static final String SETTLED = "SETTLED";
    public static final String REJECTED = "REJECTED";
}
