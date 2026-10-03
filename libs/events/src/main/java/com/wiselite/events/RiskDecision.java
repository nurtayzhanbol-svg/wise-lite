package com.wiselite.events;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by risk-engine on {@link Topics#RISK_DECISIONS}, one per FUNDED transfer. Key: {@code transferId}.
 *
 * <p>{@code decisionId} is derived from the transfer id, so a replayed or re-emitted decision has the same id and
 * is deduplicated by the consumer. A decision with a <em>different</em> id for the same transfer is a conflicting
 * (late) decision: transfer-service records it and changes nothing.
 */
public record RiskDecision(
        UUID decisionId,
        UUID transferId,
        String decision,
        List<String> reasons,
        String rulesVersion,
        Instant decidedAt) {

    public static final String TYPE = "RiskDecision";
    public static final String ALLOW = "ALLOW";
    public static final String REVIEW = "REVIEW";
    public static final String BLOCK = "BLOCK";

    public static UUID idFor(UUID transferId) {
        return UUID.nameUUIDFromBytes(("risk-decision|" + transferId).getBytes(StandardCharsets.UTF_8));
    }
}
