package com.wiselite.payout;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("wiselite.payout")
public record PayoutProperties(
        URI railsUrl,
        String callbackUrl,
        String callbackSecret,
        int maxAttempts,
        Duration baseBackoff,
        Duration maxBackoff,
        Duration callbackTimeout,
        Duration lease,
        int batchSize,
        Duration connectTimeout,
        Duration readTimeout,
        int breakerFailureThreshold,
        Duration breakerOpenDuration) {}
