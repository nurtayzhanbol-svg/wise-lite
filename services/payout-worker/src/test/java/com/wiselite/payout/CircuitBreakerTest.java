package com.wiselite.payout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-03T12:00:00Z"));
    private final CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofSeconds(30), clock);

    @Test
    void opensAfterConsecutiveFailures() {
        breaker.onFailure();
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
        assertThat(breaker.isRefusing()).isTrue();
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpenAllowsExactlyOneTrial() {
        open();
        clock.advance(Duration.ofSeconds(31));

        assertThat(breaker.isRefusing()).isFalse();
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void trialSuccessCloses() {
        open();
        clock.advance(Duration.ofSeconds(31));
        breaker.tryAcquire();
        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void trialFailureReopensForAnotherFullPeriod() {
        open();
        clock.advance(Duration.ofSeconds(31));
        breaker.tryAcquire();
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        clock.advance(Duration.ofSeconds(29));
        assertThat(breaker.tryAcquire()).isFalse();
    }

    private void open() {
        for (int i = 0; i < 3; i++) {
            breaker.onFailure();
        }
    }
}
