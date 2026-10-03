package com.wiselite.rails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.wiselite.rails.api.RailsApi;
import com.wiselite.rails.api.RailsApi.Callback;
import com.wiselite.rails.api.RailsApi.PaymentRequest;
import com.wiselite.rails.api.RailsApi.PaymentResponse;
import com.wiselite.rails.api.WebhookSignature;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "wiselite.rails.callback-secret=test-secret")
class RailsSimulatorIT {

    record Received(String body, String signature) {}

    static final ConcurrentLinkedQueue<Received> RECEIVED = new ConcurrentLinkedQueue<>();
    static final AtomicInteger FAIL_NEXT = new AtomicInteger();
    static final HttpServer RECEIVER;

    static {
        try {
            RECEIVER = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            RECEIVER.setExecutor(Executors.newCachedThreadPool());
            RECEIVER.createContext("/hook", ex -> {
                var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (FAIL_NEXT.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    ex.sendResponseHeaders(503, -1);
                } else {
                    RECEIVED.add(new Received(body, ex.getRequestHeaders().getFirst(RailsApi.SIGNATURE_HEADER)));
                    ex.sendResponseHeaders(200, -1);
                }
                ex.close();
            });
            RECEIVER.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void stop() {
        RECEIVER.stop(0);
    }

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        http.postForEntity("/admin/reset", null, Void.class);
        faults(new Faults(0, 0, 0, 0, 0, 50));
        RECEIVED.clear();
        FAIL_NEXT.set(0);
    }

    @Test
    void submissionIsIdempotentPerKey() {
        var key = UUID.randomUUID().toString();
        var first = submit(key, request(key));
        var second = submit(key, request(key));

        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody().paymentId()).isEqualTo(first.getBody().paymentId());
    }

    @Test
    void reusingAKeyWithADifferentRequestIs422() {
        var key = UUID.randomUUID().toString();
        submit(key, request(key));
        var other = new PaymentRequest(key, 999, "EUR", "Bob", "DE89370400440532013000", hookUrl());

        assertThat(submit(key, other).getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void invalidIbanIs400() {
        var key = UUID.randomUUID().toString();
        var bad = new PaymentRequest(key, 100, "EUR", "Bob", "DE00370400440532013000", hookUrl());
        assertThat(submit(key, bad).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void failureBeforeProcessingCreatesNothing() {
        faults(new Faults(1, 0, 0, 0, 0, 50));
        var key = UUID.randomUUID().toString();

        assertThat(submit(key, request(key)).getStatusCode().value()).isEqualTo(500);
        assertThat(http.getForEntity("/payments/" + key, String.class).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void failureAfterProcessingIsTheUnknownOutcome() {
        faults(new Faults(0, 1, 0, 0, 0, 50));
        var key = UUID.randomUUID().toString();

        assertThat(submit(key, request(key)).getStatusCode().value()).isEqualTo(500);
        // ...but the payment exists, and a retry with the same key finds it instead of paying twice.
        assertThat(http.getForEntity("/payments/" + key, String.class).getStatusCode().value()).isEqualTo(200);
        faults(new Faults(0, 0, 0, 0, 0, 50));
        assertThat(submit(key, request(key)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void settlesAndSendsASignedWebhook() throws Exception {
        var key = UUID.randomUUID().toString();
        submit(key, request(key));

        await().atMost(Duration.ofSeconds(10)).until(() -> receivedFor(key).size() == 1);
        var received = receivedFor(key).get(0);
        assertThat(WebhookSignature.verify("test-secret", received.body(), received.signature())).isTrue();
        var callback = json.readValue(received.body(), Callback.class);
        assertThat(callback.reference()).isEqualTo(key);
        assertThat(callback.status()).isEqualTo(RailsApi.SETTLED);
        assertThat(http.getForObject("/payments/" + key, PaymentResponse.class).status()).isEqualTo(RailsApi.SETTLED);
    }

    @Test
    void duplicateWebhooksCarryTheSameEventId() throws Exception {
        faults(new Faults(0, 0, 0, 0, 2, 50));
        var key = UUID.randomUUID().toString();
        submit(key, request(key));

        await().atMost(Duration.ofSeconds(10)).until(() -> receivedFor(key).size() == 3);
        var ids = receivedFor(key).stream().map(r -> read(r.body()).eventId()).distinct().toList();
        assertThat(ids).hasSize(1);
    }

    @Test
    void webhookDeliveryIsRetriedUntilTheReceiverAccepts() {
        FAIL_NEXT.set(2);
        var key = UUID.randomUUID().toString();
        submit(key, request(key));

        await().atMost(Duration.ofSeconds(10)).until(() -> receivedFor(key).size() == 1);
        assertThat(FAIL_NEXT).hasValue(0);
    }

    @Test
    void rejectionsAreReportedByWebhook() {
        faults(new Faults(0, 0, 1, 0, 0, 50));
        var key = UUID.randomUUID().toString();
        submit(key, request(key));

        await().atMost(Duration.ofSeconds(10)).until(() -> receivedFor(key).size() == 1);
        assertThat(read(receivedFor(key).get(0).body()).status()).isEqualTo(RailsApi.REJECTED);
    }

    private Callback read(String body) {
        try {
            return json.readValue(body, Callback.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void faults(Faults f) {
        http.postForEntity("/admin/faults", f, Faults.class);
    }

    private ResponseEntity<PaymentResponse> submit(String key, PaymentRequest request) {
        var headers = new HttpHeaders();
        headers.set(RailsApi.IDEMPOTENCY_KEY, key);
        return http.postForEntity("/payments", new HttpEntity<>(request, headers), PaymentResponse.class);
    }

    private static PaymentRequest request(String reference) {
        return new PaymentRequest(reference, 2_500, "EUR", "Bob", "DE89370400440532013000", hookUrl());
    }

    private static String hookUrl() {
        return "http://localhost:" + RECEIVER.getAddress().getPort() + "/hook";
    }

    /** Webhooks from earlier tests' payments may still arrive: always filter by reference. */
    private List<Received> receivedFor(String key) {
        return RECEIVED.stream().filter(r -> key.equals(read(r.body()).reference())).toList();
    }
}
