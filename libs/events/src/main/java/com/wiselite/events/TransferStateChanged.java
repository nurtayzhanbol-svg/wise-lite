package com.wiselite.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published on {@link Topics#TRANSFER_EVENTS} every time a transfer changes state.
 * Kafka key: {@code transferId}, so all events of one transfer land on one partition, in order.
 *
 * <p>Consumers must be idempotent: the same event (same {@code eventId}) can be delivered more
 * than once (outbox relay retries, consumer rebalances).
 */
public record TransferStateChanged(
        UUID eventId,
        UUID transferId,
        UUID ownerId,
        String fromState,
        String toState,
        long amountMinor,
        String currency,
        String recipientName,
        String recipientIban,
        String reason,
        Instant occurredAt) {

    public static final String TYPE = "TransferStateChanged";
}
