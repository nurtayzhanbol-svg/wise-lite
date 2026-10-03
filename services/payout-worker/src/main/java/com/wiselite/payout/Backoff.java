package com.wiselite.payout;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with "equal jitter": delay in [cap/2, cap], cap = min(max, base * 2^(n-1)).
 * Jitter spreads retries out, so 10k payouts that failed together don't retry together.
 */
public final class Backoff {

    private Backoff() {}

    public static Duration delay(int attempt, Duration base, Duration max) {
        return delay(attempt, base, max, ThreadLocalRandom.current().nextDouble());
    }

    static Duration delay(int attempt, Duration base, Duration max, double random) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 30);
        long capMillis = Math.min(max.toMillis(), base.toMillis() * (1L << exponent));
        long half = capMillis / 2;
        return Duration.ofMillis(half + (long) ((capMillis - half) * random));
    }
}
