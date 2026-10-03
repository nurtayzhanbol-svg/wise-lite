package com.wiselite.payout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Minimal circuit breaker (deliberately hand-written to study; Resilience4j in production).
 * CLOSED: calls flow, consecutive failures are counted. OPEN: calls are refused until
 * {@code openDuration} passes. HALF_OPEN: exactly one trial call; success closes, failure re-opens.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt = Instant.EPOCH;
    private boolean trialInFlight;

    public CircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = clock;
    }

    public synchronized boolean tryAcquire() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> {
                if (Instant.now(clock).isBefore(openedAt.plus(openDuration))) {
                    yield false;
                }
                state = State.HALF_OPEN;
                trialInFlight = true;
                yield true;
            }
            case HALF_OPEN -> {
                if (trialInFlight) {
                    yield false;
                }
                trialInFlight = true;
                yield true;
            }
        };
    }

    /** True if a call right now would certainly be refused (no side effects). */
    public synchronized boolean isRefusing() {
        return (state == State.OPEN && Instant.now(clock).isBefore(openedAt.plus(openDuration)))
                || (state == State.HALF_OPEN && trialInFlight);
    }

    public synchronized void onSuccess() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        trialInFlight = false;
    }

    public synchronized void onFailure() {
        trialInFlight = false;
        if (state == State.HALF_OPEN || ++consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = Instant.now(clock);
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized void reset() {
        onSuccess();
    }
}
