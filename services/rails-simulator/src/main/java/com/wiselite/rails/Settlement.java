package com.wiselite.rails;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiselite.rails.api.RailsApi;
import com.wiselite.rails.api.WebhookSignature;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Settles accepted payments after a delay and delivers webhooks at-least-once, with retries. */
@Component
class Settlement {

    private static final Logger log = LoggerFactory.getLogger(Settlement.class);
    private static final int MAX_DELIVERY_ATTEMPTS = 6;

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final RestClient http;
    private final ObjectMapper json;
    private final Random random;
    private final String secret;

    Settlement(RestClient.Builder builder, ObjectMapper json, Random random, @Value("${wiselite.rails.callback-secret}") String secret) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.http = builder.requestFactory(factory).build();
        this.json = json;
        this.random = random;
        this.secret = secret;
    }

    void schedule(Payment payment, Faults faults) {
        executor.schedule(() -> settle(payment, faults), faults.settleDelayMs(), TimeUnit.MILLISECONDS);
    }

    private void settle(Payment payment, Faults faults) {
        payment.complete(random.nextDouble() < faults.rejectRate() ? RailsApi.REJECTED : RailsApi.SETTLED);
        var url = payment.request().callbackUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        for (int i = 0; i <= faults.duplicateCallbacks(); i++) {
            deliver(url, payment.callback(), 1);
        }
    }

    private void deliver(String url, RailsApi.Callback callback, int attempt) {
        try {
            var body = json.writeValueAsString(callback);
            http.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .header(RailsApi.SIGNATURE_HEADER, WebhookSignature.sign(secret, body))
                    .body(body).retrieve().toBodilessEntity();
        } catch (Exception e) {
            if (attempt >= MAX_DELIVERY_ATTEMPTS) {
                log.warn("Giving up on webhook {} after {} attempts: {}", callback.eventId(), attempt, e.toString());
                return;
            }
            executor.schedule(() -> deliver(url, callback, attempt + 1), 100L << attempt, TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
