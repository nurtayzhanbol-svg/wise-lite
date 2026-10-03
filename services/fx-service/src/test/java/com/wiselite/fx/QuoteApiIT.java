package com.wiselite.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"wiselite.fx.refresh-interval=PT1H", "wiselite.fx.max-staleness=1h", "wiselite.fx.quote-ttl=30s"})
@Import(QuoteApiIT.Config.class)
class QuoteApiIT {

    /** Stub ECB endpoint that tests can take down. */
    static final HttpServer ECB;
    static volatile boolean ecbDown;

    static {
        try {
            var xml = EcbRateProviderTest.sample();
            ECB = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            ECB.createContext("/rates.xml", ex -> {
                if (ecbDown) {
                    ex.sendResponseHeaders(503, -1);
                } else {
                    ex.sendResponseHeaders(200, xml.length);
                    ex.getResponseBody().write(xml);
                }
                ex.close();
            });
            ECB.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void ecbUrl(DynamicPropertyRegistry registry) {
        registry.add("wiselite.fx.ecb-url", () -> "http://localhost:" + ECB.getAddress().getPort() + "/rates.xml");
    }

    @AfterAll
    static void stopEcb() {
        ECB.stop(0);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-10-02T15:00:00Z"));
        }
    }

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;

    @BeforeEach
    void ecbUp() {
        ecbDown = false;
    }

    @Test
    void quoteShowsFeeRateAndTargetAmount() throws Exception {
        var r = createQuote("GBP", "USD", "1000.00");

        assertThat(r.getStatusCode().value()).isEqualTo(201);
        var q = read(r);
        assertThat(q.get("fee").decimalValue()).isEqualByComparingTo("4.30");
        assertThat(q.get("amountConverted").decimalValue()).isEqualByComparingTo("995.70");
        assertThat(q.get("rate").decimalValue()).isEqualByComparingTo("1.31235154");
        assertThat(q.get("targetAmount").decimalValue()).isEqualByComparingTo("1306.70"); // 995.70 * 1.31235154 = 1306.708..., rounded down
        assertThat(q.get("rateAsOf").asText()).isEqualTo("2026-10-02");
        assertThat(q.get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void invalidRequestsAreRejected() {
        assertThat(createQuote("EUR", "EUR", "10").getStatusCode().value()).isEqualTo(400);
        assertThat(createQuote("EUR", "JPY", "10.001").getStatusCode().value()).isEqualTo(400);
        assertThat(createQuote("EUR", "KZT", "10").getStatusCode().value()).isEqualTo(400);
        assertThat(createQuote("EUR", "USD", "0.20").getStatusCode().value()).isEqualTo(422); // fee >= amount
    }

    @Test
    void quoteExpiresAfterTtl() throws Exception {
        var id = read(createQuote("EUR", "USD", "100")).get("id").asText();

        clock.advance(Duration.ofSeconds(31));

        assertThat(read(http.getForEntity("/quotes/" + id, String.class)).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(consume(id, "transfer-1").getStatusCode().value()).isEqualTo(410);
    }

    @Test
    void quoteIsSingleUseButIdempotentForTheSameConsumer() throws Exception {
        var id = read(createQuote("EUR", "USD", "100")).get("id").asText();

        assertThat(consume(id, "transfer-1").getStatusCode().value()).isEqualTo(200);
        assertThat(consume(id, "transfer-1").getStatusCode().value()).isEqualTo(200);
        assertThat(consume(id, "transfer-2").getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void concurrentConsumersExactlyOneWins() throws Exception {
        var id = read(createQuote("EUR", "HUF", "100")).get("id").asText();
        var start = new CountDownLatch(1);
        var codes = new ArrayList<Integer>();
        try (var pool = Executors.newFixedThreadPool(12)) {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 12; i++) {
                var ref = "transfer-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return consume(id, ref).getStatusCode().value();
                }));
            }
            start.countDown();
            for (var f : futures) {
                codes.add(f.get());
            }
        }
        assertThat(codes).filteredOn(c -> c == 200).hasSize(1);
        assertThat(codes).filteredOn(c -> c == 409).hasSize(11);
    }

    @Test
    void providerOutageDegradesThenFailsClosed() {
        createQuote("EUR", "USD", "100"); // make sure a snapshot exists
        ecbDown = true;

        clock.advance(Duration.ofMinutes(30));
        assertThat(createQuote("EUR", "USD", "100").getStatusCode().value()).isEqualTo(201); // last good snapshot

        clock.advance(Duration.ofMinutes(31));
        var refused = createQuote("EUR", "USD", "100");
        assertThat(refused.getStatusCode().value()).isEqualTo(503);
        assertThat(refused.getHeaders().getFirst("Retry-After")).isEqualTo("30");

        ecbDown = false;
        assertThat(createQuote("EUR", "USD", "100").getStatusCode().value()).isEqualTo(201);
    }

    private ResponseEntity<String> createQuote(String from, String to, String amount) {
        return http.postForEntity("/quotes", Map.of("sourceCurrency", from, "targetCurrency", to,
                "sourceAmount", new java.math.BigDecimal(amount)), String.class);
    }

    private ResponseEntity<String> consume(String id, String ref) {
        return http.postForEntity("/quotes/" + id + "/consume", Map.of("consumerReference", ref), String.class);
    }

    private JsonNode read(ResponseEntity<String> r) throws Exception {
        return json.readTree(r.getBody());
    }
}
