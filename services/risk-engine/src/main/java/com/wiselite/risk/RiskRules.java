package com.wiselite.risk;

import java.time.Duration;
import java.util.List;

/** Thresholds of the detection rules and the per-transfer decision. Plain values so the topology is testable without Spring. */
public record RiskRules(
        Duration velocityWindow,
        int maxTransfersPerWindow,
        Duration volumeWindow,
        long maxVolumeMinor,
        Duration recipientWindow,
        int maxSendersPerRecipient,
        Duration grace,
        Duration dedupeRetention,
        long reviewAmountMinor,
        long blockAmountMinor,
        List<String> blockedIbans) {

    public List<String> blockedIbans() {
        return blockedIbans == null ? List.of() : blockedIbans;
    }
}
