package com.wiselite.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-02T15:00:00Z"));
    private final AtomicBoolean providerDown = new AtomicBoolean();
    private final AtomicInteger calls = new AtomicInteger();
    private final RateService rates = new RateService(() -> {
        calls.incrementAndGet();
        if (providerDown.get()) {
            throw new IllegalStateException("ECB down");
        }
        try {
            return EcbRateProvider.parse(EcbRateProviderTest.sample());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }, clock, Duration.ofHours(1));

    @Test
    void fetchesLazilyOnFirstUse() {
        assertThat(rates.current().perEur()).containsKey("USD");
        rates.current();
        assertThat(calls).hasValue(1);
    }

    @Test
    void keepsServingTheLastGoodSnapshotWhileWithinStaleness() {
        rates.current();
        providerDown.set(true);
        clock.advance(Duration.ofMinutes(30));
        rates.refresh();

        assertThat(rates.current().fetchedAt()).isEqualTo(Instant.parse("2026-10-02T15:00:00Z"));
    }

    @Test
    void refusesOnceTheSnapshotIsTooOldThenRecovers() {
        rates.current();
        providerDown.set(true);
        clock.advance(Duration.ofMinutes(61));

        assertThatThrownBy(rates::current).isInstanceOf(RatesUnavailableException.class);

        providerDown.set(false);
        assertThat(rates.current().fetchedAt()).isEqualTo(clock.instant());
    }

    @Test
    void noSnapshotAndProviderDownIsUnavailable() {
        providerDown.set(true);
        assertThatThrownBy(rates::current).isInstanceOf(RatesUnavailableException.class);
    }
}
