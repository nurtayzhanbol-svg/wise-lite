package com.wiselite.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the outbox. Disabled in most tests, which call {@link OutboxRelay#publishBatch()} directly. */
@Component
@ConditionalOnProperty(prefix = "wiselite.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${wiselite.outbox.relay.interval:PT0.2S}")
    public void drain() {
        try {
            int published;
            do {
                published = relay.publishBatch();
            } while (published > 0);
        } catch (RuntimeException e) {
            // Kafka down or slow: rows stay in the outbox and are retried on the next tick.
            log.warn("Outbox relay failed, will retry: {}", e.toString());
        }
    }
}
