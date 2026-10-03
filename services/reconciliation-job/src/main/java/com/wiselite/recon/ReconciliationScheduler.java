package com.wiselite.recon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "wiselite.recon.scheduler.enabled", havingValue = "true", matchIfMissing = true)
class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService service;

    ReconciliationScheduler(ReconciliationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${wiselite.recon.scheduler.cron:0 0 2 * * *}")
    void nightly() {
        try {
            service.run();
        } catch (RuntimeException e) {
            log.error("Reconciliation run failed", e);
        }
    }
}
