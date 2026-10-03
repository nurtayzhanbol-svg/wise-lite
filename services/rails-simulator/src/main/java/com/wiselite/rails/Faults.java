package com.wiselite.rails;

/**
 * Fault-injection knobs. Rates are probabilities in [0, 1].
 *
 * @param failBeforeRate respond 500 without creating the payment
 * @param failAfterRate create the payment, then respond 500: the caller cannot know it succeeded
 * @param rejectRate settle as REJECTED instead of SETTLED
 * @param latencyMs delay before responding (drive callers into their timeouts)
 * @param duplicateCallbacks extra copies of every webhook (same eventId)
 * @param settleDelayMs time from acceptance to final outcome
 */
public record Faults(
        double failBeforeRate, double failAfterRate, double rejectRate, long latencyMs, int duplicateCallbacks, long settleDelayMs) {

    public static Faults none() {
        return new Faults(0, 0, 0, 0, 0, 2_000);
    }
}
