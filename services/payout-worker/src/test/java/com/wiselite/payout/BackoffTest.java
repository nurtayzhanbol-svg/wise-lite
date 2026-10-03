package com.wiselite.payout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

    private static final Duration BASE = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofMinutes(5);

    @Test
    void growsExponentiallyWithinJitterBounds() {
        assertThat(Backoff.delay(1, BASE, MAX, 0.0)).isEqualTo(Duration.ofMillis(500));
        assertThat(Backoff.delay(1, BASE, MAX, 1.0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(Backoff.delay(4, BASE, MAX, 0.0)).isEqualTo(Duration.ofSeconds(4));
        assertThat(Backoff.delay(4, BASE, MAX, 1.0)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void isCappedAndNeverOverflows() {
        assertThat(Backoff.delay(20, BASE, MAX, 1.0)).isEqualTo(MAX);
        assertThat(Backoff.delay(1_000, BASE, MAX, 1.0)).isEqualTo(MAX);
    }
}
