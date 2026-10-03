package com.wiselite.payout;

import com.wiselite.rails.api.RailsApi;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Submits due payouts to the rail and records what we learned. */
@Component
public class PayoutDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PayoutDispatcher.class);

    private final PayoutStore store;
    private final RailsClient rails;
    private final CircuitBreaker breaker;
    private final PayoutProperties properties;
    private final Clock clock;

    public PayoutDispatcher(PayoutStore store, RailsClient rails, CircuitBreaker breaker, PayoutProperties properties, Clock clock) {
        this.store = store;
        this.rails = rails;
        this.breaker = breaker;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return number of rail calls made */
    public int dispatchDue() {
        if (breaker.isRefusing()) {
            return 0;
        }
        int calls = 0;
        for (var payout : store.claimDue(properties.batchSize(), properties.lease())) {
            if (!breaker.tryAcquire()) {
                store.release(payout.transferId());
                continue;
            }
            calls++;
            handle(payout, rails.submit(payout));
        }
        return calls;
    }

    private void handle(PayoutStore.ClaimedPayout payout, RailsClient.Result result) {
        var id = payout.transferId();
        var now = Instant.now(clock);
        boolean exhausted = payout.attempts() >= properties.maxAttempts();
        switch (result) {
            case RailsClient.Accepted a -> {
                breaker.onSuccess();
                switch (a.status()) {
                    case RailsApi.SETTLED -> store.settle(id, PayoutStatus.SETTLED, null);
                    case RailsApi.REJECTED -> store.settle(id, PayoutStatus.REJECTED, "rejected by rail");
                    case RailsApi.PENDING -> {
                        if (exhausted) {
                            store.markManualReview(id, "still pending at the rail after " + payout.attempts() + " checks");
                        } else {
                            store.markSubmitted(id, a.paymentId(), now.plus(properties.callbackTimeout()));
                        }
                    }
                    default -> store.markManualReview(id, "unexpected rail status " + a.status());
                }
            }
            case RailsClient.Rejected r -> {
                breaker.onSuccess();
                store.settle(id, PayoutStatus.REJECTED, r.reason());
            }
            case RailsClient.Anomaly an -> {
                breaker.onSuccess();
                log.error("Payout {} needs manual review: {}", id, an.reason());
                store.markManualReview(id, an.reason());
            }
            case RailsClient.Unknown u -> {
                breaker.onFailure();
                if (exhausted) {
                    log.error("Payout {} outcome unknown after {} attempts: {}", id, payout.attempts(), u.reason());
                    store.markManualReview(id, u.reason());
                } else {
                    var delay = Backoff.delay(payout.attempts(), properties.baseBackoff(), properties.maxBackoff());
                    store.markUnknown(id, u.reason(), now.plus(delay));
                }
            }
        }
    }
}
