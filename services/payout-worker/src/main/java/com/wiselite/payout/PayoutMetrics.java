package com.wiselite.payout;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Payout state as gauges, refreshed periodically (one GROUP BY instead of one query per scrape and status). */
@Component
public class PayoutMetrics {

    private static final Logger log = LoggerFactory.getLogger(PayoutMetrics.class);

    private final JdbcClient jdbc;
    private final MultiGauge byStatus;
    private final AtomicLong oldestUnresolvedSeconds = new AtomicLong();

    public PayoutMetrics(JdbcClient jdbc, MeterRegistry registry, CircuitBreaker breaker) {
        this.jdbc = jdbc;
        this.byStatus = MultiGauge.builder("wiselite.payouts").description("Payouts by status").register(registry);
        Gauge.builder("wiselite.payouts.oldest.unresolved.age", oldestUnresolvedSeconds, AtomicLong::get)
                .baseUnit("seconds").description("Age of the oldest payout without a final outcome").register(registry);
        Gauge.builder("wiselite.rails.circuit.state", breaker, b -> b.state().ordinal())
                .description("0 closed, 1 open, 2 half-open").register(registry);
    }

    @Scheduled(fixedDelayString = "${wiselite.payout.metrics-interval:PT10S}")
    public void refresh() {
        try {
            Map<String, Long> counts = jdbc.sql("SELECT status, count(*) FROM payouts GROUP BY status")
                    .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2))).list().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            byStatus.register(Arrays.stream(PayoutStatus.values())
                    .<MultiGauge.Row<?>>map(s -> MultiGauge.Row.of(Tags.of("status", s.name()), counts.getOrDefault(s.name(), 0L)))
                    .toList(), true);
            oldestUnresolvedSeconds.set(jdbc.sql("""
                            SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0)::bigint FROM payouts
                            WHERE status IN ('PENDING', 'SUBMITTED', 'UNKNOWN', 'MANUAL_REVIEW')""")
                    .query(Long.class).single());
        } catch (RuntimeException e) {
            log.warn("Payout metrics refresh failed: {}", e.toString());
        }
    }
}
