package com.wiselite.payout;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.wiselite.events.TransferStateChanged;
import com.wiselite.rails.api.RailsApi;
import com.wiselite.rails.api.WebhookSignature;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Drives the dispatcher against a programmable fake rail, to hit each failure mode on purpose. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "wiselite.payout.dispatcher.enabled=false",
        "wiselite.payout.max-attempts=3",
        "wiselite.payout.breaker-failure-threshold=3",
        "wiselite.payout.read-timeout=PT0.5S",
        "wiselite.payout.base-backoff=PT0.1S",
        "wiselite.payout.callback-secret=test-secret"})
@Import(TestcontainersConfig.class)
class PayoutDispatchIT {

    record StubResponse(int status, String body, long delayMs) {}

    interface Responder {
        StubResponse respond(String idempotencyKey) throws Exception;
    }

    static final List<String> REQUEST_KEYS = new CopyOnWriteArrayList<>();
    static volatile Responder responder = key -> new StubResponse(500, "{}", 0);
    static final HttpServer RAIL;

    static {
        try {
            RAIL = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            RAIL.setExecutor(Executors.newCachedThreadPool());
            RAIL.createContext("/payments", ex -> {
                ex.getRequestBody().readAllBytes();
                var key = ex.getRequestHeaders().getFirst(RailsApi.IDEMPOTENCY_KEY);
                REQUEST_KEYS.add(key);
                try {
                    var r = responder.respond(key);
                    Thread.sleep(r.delayMs());
                    var bytes = r.body().getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(r.status(), bytes.length);
                    ex.getResponseBody().write(bytes);
                } catch (Exception e) {
                    ex.sendResponseHeaders(599, -1);
                } finally {
                    ex.close();
                }
            });
            RAIL.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void railUrl(DynamicPropertyRegistry registry) {
        registry.add("wiselite.payout.rails-url", () -> "http://localhost:" + RAIL.getAddress().getPort());
    }

    @AfterAll
    static void stopRail() {
        RAIL.stop(0);
    }

    @Autowired PayoutService payouts;
    @Autowired PayoutDispatcher dispatcher;
    @Autowired CircuitBreaker breaker;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM payouts").update();
        jdbc.sql("DELETE FROM processed_events").update();
        REQUEST_KEYS.clear();
        breaker.reset();
    }

    @Test
    void submitThenSignedWebhookSettles_duplicatesAndConflictsAreHarmless() throws Exception {
        responder = key -> accepted(key, RailsApi.PENDING);
        var id = newPayout();

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(status(id)).isEqualTo("SUBMITTED");

        var settled = callbackBody(UUID.randomUUID(), id, RailsApi.SETTLED);
        assertThat(postCallback(settled, sign(settled)).body()).contains("APPLIED");
        assertThat(postCallback(settled, sign(settled)).body()).contains("DUPLICATE");
        assertThat(status(id)).isEqualTo("SETTLED");
        // Exactly one PayoutStatusChanged for transfer-service, despite the duplicate webhook.
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE event_key = ?").param(id.toString())
                .query(Long.class).single()).isEqualTo(1);

        var conflicting = callbackBody(UUID.randomUUID(), id, RailsApi.REJECTED);
        var response = postCallback(conflicting, sign(conflicting));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("CONFLICT");
        assertThat(status(id)).isEqualTo("SETTLED");
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE event_key = ?").param(id.toString())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void unknownOutcomeIsResolvedByRetryingWithTheSameIdempotencyKey() throws Exception {
        var calls = new AtomicInteger();
        // First call: the rail processed it but answered 500 (the classic unknown outcome).
        responder = key -> calls.incrementAndGet() == 1 ? new StubResponse(500, "{}", 0) : accepted(key, RailsApi.SETTLED);
        var id = newPayout();

        dispatcher.dispatchDue();
        assertThat(status(id)).isEqualTo("UNKNOWN");
        makeDue(id);
        dispatcher.dispatchDue();

        assertThat(status(id)).isEqualTo("SETTLED");
        assertThat(REQUEST_KEYS).containsExactly(id.toString(), id.toString());
        assertThat(attempts(id)).isEqualTo(2);
    }

    @Test
    void timeoutIsAnUnknownOutcomeWithBackoff() throws Exception {
        responder = key -> new StubResponse(202, "{}", 1_500);
        var id = newPayout();

        dispatcher.dispatchDue();

        assertThat(status(id)).isEqualTo("UNKNOWN");
        var next = jdbc.sql("SELECT next_attempt_at FROM payouts WHERE transfer_id = ?").param(id)
                .query(java.sql.Timestamp.class).single().toInstant();
        assertThat(next).isAfter(Instant.now().minusMillis(400));
    }

    @Test
    void badRequestIsAPermanentRejection() throws Exception {
        responder = key -> new StubResponse(400, "{\"error\":\"invalid IBAN\"}", 0);
        var id = newPayout();

        dispatcher.dispatchDue();
        makeDue(id);

        assertThat(status(id)).isEqualTo("REJECTED");
        assertThat(dispatcher.dispatchDue()).isZero();
    }

    @Test
    void contractViolationGoesToManualReview() throws Exception {
        responder = key -> new StubResponse(422, "{\"error\":\"key reused\"}", 0);
        var id = newPayout();

        dispatcher.dispatchDue();

        assertThat(status(id)).isEqualTo("MANUAL_REVIEW");
    }

    @Test
    void retriesStopAtMaxAttempts_thenALateWebhookStillResolves() throws Exception {
        responder = key -> new StubResponse(503, "{}", 0);
        var id = newPayout();

        for (int i = 0; i < 3; i++) {
            breaker.reset();
            makeDue(id);
            dispatcher.dispatchDue();
        }
        assertThat(status(id)).isEqualTo("MANUAL_REVIEW"); // not FAILED: the money may have moved
        assertThat(REQUEST_KEYS).hasSize(3);

        var late = callbackBody(UUID.randomUUID(), id, RailsApi.SETTLED);
        postCallback(late, sign(late));
        assertThat(status(id)).isEqualTo("SETTLED");
    }

    @Test
    void circuitBreakerStopsHammeringAFailingRail() throws Exception {
        responder = key -> new StubResponse(503, "{}", 0);
        for (int i = 0; i < 6; i++) {
            newPayout();
        }

        assertThat(dispatcher.dispatchDue()).isEqualTo(3);

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(REQUEST_KEYS).hasSize(3);
        assertThat(jdbc.sql("SELECT count(*) FROM payouts WHERE status = 'PENDING' AND attempts = 0").query(Long.class).single())
                .isEqualTo(3);
        assertThat(dispatcher.dispatchDue()).isZero();
    }

    @Test
    void webhookArrivingBeforeTheSubmitResponseIsNotOverwritten() throws Exception {
        // The rail settles and calls us back *while* our submit request is still in flight.
        responder = key -> {
            var body = callbackBody(UUID.randomUUID(), UUID.fromString(key), RailsApi.SETTLED);
            postCallback(body, sign(body));
            return accepted(key, RailsApi.PENDING);
        };
        var id = newPayout();

        dispatcher.dispatchDue();

        assertThat(status(id)).isEqualTo("SETTLED");
    }

    @Test
    void unsignedOrTamperedWebhooksAreRejected() throws Exception {
        var id = newPayout();
        var body = callbackBody(UUID.randomUUID(), id, RailsApi.SETTLED);

        assertThat(postCallback(body, null).statusCode()).isEqualTo(401);
        assertThat(postCallback(body.replace("SETTLED", "REJECTED"), sign(body)).statusCode()).isEqualTo(401);
        assertThat(status(id)).isEqualTo("PENDING");
    }

    @Test
    void webhookForAnUnknownPayoutIs404AndCanBeRetried() throws Exception {
        var eventId = UUID.randomUUID();
        var body = callbackBody(eventId, UUID.randomUUID(), RailsApi.SETTLED);

        assertThat(postCallback(body, sign(body)).statusCode()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = ?").param(eventId).query(Long.class).single())
                .isZero();
    }

    private UUID newPayout() {
        var id = UUID.randomUUID();
        payouts.handle(new TransferStateChanged(UUID.randomUUID(), id, UUID.randomUUID(), "CREATED", "FUNDED", 2_500, "EUR",
                "Bob", "DE89370400440532013000", null, Instant.now()));
        return id;
    }

    private void makeDue(UUID id) {
        jdbc.sql("UPDATE payouts SET next_attempt_at = now() - interval '1 second' WHERE transfer_id = ?").param(id).update();
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM payouts WHERE transfer_id = ?").param(id).query(String.class).single();
    }

    private int attempts(UUID id) {
        return jdbc.sql("SELECT attempts FROM payouts WHERE transfer_id = ?").param(id).query(Integer.class).single();
    }

    private StubResponse accepted(String key, String status) throws Exception {
        return new StubResponse(202, json.writeValueAsString(new RailsApi.PaymentResponse("pay-" + key, key, status)), 0);
    }

    private String callbackBody(UUID eventId, UUID reference, String status) throws Exception {
        return json.writeValueAsString(new RailsApi.Callback(eventId.toString(), "pay-" + reference, reference.toString(), status));
    }

    private static String sign(String body) {
        return WebhookSignature.sign("test-secret", body);
    }

    private HttpResponse<String> postCallback(String body, String signature) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/rails/callbacks"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            request.header(RailsApi.SIGNATURE_HEADER, signature);
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
