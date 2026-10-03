package com.wiselite.transfer.risk;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The first sweep waits one full timeout after startup: after our own outage the decisions are sitting in
 * Kafka, and the consumer must get a chance to apply them before we declare them missing.
 */
@Component
@ConditionalOnProperty(name = "wiselite.risk.timeout-sweep.enabled", havingValue = "true", matchIfMissing = true)
public class RiskTimeoutScheduler {

    private final RiskTimeoutService service;
    private final Duration timeout;

    public RiskTimeoutScheduler(RiskTimeoutService service, @Value("${wiselite.risk.decision-timeout:PT5M}") Duration timeout) {
        this.service = service;
        this.timeout = timeout;
    }

    @Scheduled(initialDelayString = "${wiselite.risk.decision-timeout:PT5M}",
            fixedDelayString = "${wiselite.risk.timeout-sweep.interval:PT5S}")
    public void sweep() {
        service.holdOverdue(timeout);
    }
}
