package com.wiselite.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Backlog = the health signal of the outbox. "Oldest unpublished age" is the better alert:
 * a large but moving backlog is fine, a small one that is stuck is not.
 * Both are queried on scrape (cheap thanks to the partial index on unpublished rows).
 */
public class OutboxMetrics {

    public OutboxMetrics(OutboxRelay relay, JdbcClient jdbc, MeterRegistry registry) {
        Gauge.builder("wiselite.outbox.unpublished", relay, OutboxRelay::unpublishedCount)
                .description("Outbox events not yet published to Kafka")
                .register(registry);
        Gauge.builder("wiselite.outbox.oldest.unpublished.age", jdbc, j -> j.sql("""
                        SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0)
                        FROM outbox_events WHERE published_at IS NULL""").query(Double.class).single())
                .baseUnit("seconds")
                .description("Age of the oldest unpublished outbox event")
                .register(registry);
    }
}
