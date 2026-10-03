package com.wiselite.fx;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Holds the latest good rate snapshot. Degraded mode: if the provider fails, we keep quoting on
 * the last good snapshot for up to {@code maxStaleness}, then refuse (503) rather than quote
 * on rates that may have moved.
 */
@Service
public class RateService {

    private static final Logger log = LoggerFactory.getLogger(RateService.class);

    private final Supplier<RateSnapshot> provider;
    private final Clock clock;
    private final Duration maxStaleness;
    private final AtomicReference<RateSnapshot> latest = new AtomicReference<>();

    @Autowired
    public RateService(EcbRateProvider provider, Clock clock, FxProperties properties) {
        this(provider::fetch, clock, properties.maxStaleness());
    }

    RateService(Supplier<RateSnapshot> provider, Clock clock, Duration maxStaleness) {
        this.provider = provider;
        this.clock = clock;
        this.maxStaleness = maxStaleness;
    }

    @Scheduled(fixedDelayString = "${wiselite.fx.refresh-interval:PT10M}")
    public synchronized void refresh() {
        try {
            latest.set(provider.get().fetchedAt(Instant.now(clock)));
        } catch (RuntimeException e) {
            log.warn("Rate refresh failed, keeping last snapshot: {}", e.toString());
        }
    }

    public RateSnapshot current() {
        var snapshot = latest.get();
        if (snapshot == null || isStale(snapshot)) {
            refresh(); // synchronized: concurrent callers don't stampede the provider
            snapshot = latest.get();
        }
        if (snapshot == null || isStale(snapshot)) {
            throw new RatesUnavailableException();
        }
        return snapshot;
    }

    private boolean isStale(RateSnapshot s) {
        return Duration.between(s.fetchedAt(), Instant.now(clock)).compareTo(maxStaleness) > 0;
    }
}
