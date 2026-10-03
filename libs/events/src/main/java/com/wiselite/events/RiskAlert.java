package com.wiselite.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published on {@link Topics#RISK_ALERTS} by risk-engine. Key: {@code subject}.
 * {@code alertId} is derived from (rule, subject, window start), so reprocessing the same input yields
 * the same id and downstream consumers can deduplicate.
 */
public record RiskAlert(
        UUID alertId,
        String rule,
        String subject,
        String details,
        List<UUID> transferIds,
        Instant windowStart,
        Instant windowEnd) {

    public static final String VELOCITY = "VELOCITY";
    public static final String DAILY_VOLUME = "DAILY_VOLUME";
    public static final String MULE_RECIPIENT = "MULE_RECIPIENT";
}
