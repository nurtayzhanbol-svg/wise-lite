package com.wiselite.payout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "wiselite.payout.dispatcher.enabled", havingValue = "true", matchIfMissing = true)
class PayoutDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(PayoutDispatchScheduler.class);

    private final PayoutDispatcher dispatcher;

    PayoutDispatchScheduler(PayoutDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${wiselite.payout.dispatcher.interval:PT1S}")
    void tick() {
        try {
            dispatcher.dispatchDue();
        } catch (RuntimeException e) {
            log.warn("Payout dispatch failed, will retry next tick: {}", e.toString());
        }
    }
}
