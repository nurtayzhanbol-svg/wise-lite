package com.wiselite.risk;

import java.time.Duration;

/** Thresholds of the three detection rules. Plain values so the topology is testable without Spring. */
public record RiskRules(
        Duration velocityWindow,
        int maxTransfersPerWindow,
        Duration volumeWindow,
        long maxVolumeMinor,
        Duration recipientWindow,
        int maxSendersPerRecipient,
        Duration grace,
        Duration dedupeRetention) {}
