package com.wiselite.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The whole system, as separate processes, under concurrent load while things break:
 * rail fails before/after processing, rejects, duplicates webhooks; Kafka is paused;
 * payout-worker is killed with SIGKILL and restarted. Afterwards every transfer must be final,
 * money conserved, the rail must hold at most one payment per transfer, and reconciliation must be clean.
 */
class ChaosSystemTest {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static final File LOGS = new File("build/system-test-logs");
    static final Map<String, Process> processes = new ConcurrentHashMap<>();
    static final Map<String, Integer> ports = new HashMap<>();

    static final int CUSTOMERS = 10;
    static final int TRANSFERS = 200;
    static final long TOP_UP_MINOR = 1_000_00;

    @BeforeAll
    static void startSystem() throws Exception {
        if (LOGS.exists()) {
            for (var f : LOGS.listFiles()) {
                f.delete();
            }
        }
        LOGS.mkdirs();
        PG.start();
        KAFKA.start();
        try (var c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); var st = c.createStatement()) {
            for (var db : List.of("transfers", "payouts", "recon")) {
                st.execute("CREATE DATABASE " + db);
            }
        }
        for (var s : List.of("transfer-service", "payout-worker", "rails-simulator", "reconciliation-job")) {
            ports.put(s, freePort());
        }
        for (var s : ports.keySet()) {
            start(s);
        }
        for (var s : ports.keySet()) {
            awaitHealthy(s);
        }
    }

    @AfterAll
    static void stopSystem() {
        processes.values().forEach(Process::destroyForcibly);
    }

    @Test
    void moneyIsConservedAndBooksReconcileUnderFaults() throws Exception {
        post("rails-simulator", "/admin/faults", Map.of(), Map.of(
                "failBeforeRate", 0.1, "failAfterRate", 0.2, "rejectRate", 0.1,
                "latencyMs", 20, "duplicateCallbacks", 2, "settleDelayMs", 300));

        Map<UUID, UUID> accountOwner = new HashMap<>();
        for (int i = 0; i < CUSTOMERS; i++) {
            var owner = UUID.randomUUID();
            var account = UUID.fromString(post("transfer-service", "/accounts", Map.of(),
                    Map.of("ownerId", owner, "currency", "EUR")).get("id").asText());
            post("transfer-service", "/internal/accounts/" + account + "/top-ups", Map.of("Idempotency-Key", UUID.randomUUID().toString()),
                    Map.of("amount", TOP_UP_MINOR / 100, "currency", "EUR"));
            accountOwner.put(account, owner);
        }
        var accounts = new ArrayList<>(accountOwner.keySet());

        var random = new Random(7);
        record Created(UUID id, UUID owner, long amountMinor) {}
        var created = new ArrayList<Future<Created>>();
        long started = System.nanoTime();
        // Separate pools: tasks of `pool` submit to `retries`, and must not submit to a pool that is shutting down.
        try (var retries = Executors.newFixedThreadPool(16); var pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < TRANSFERS; i++) {
                var account = accounts.get(random.nextInt(accounts.size()));
                var owner = accountOwner.get(account);
                long amountMinor = 100 + random.nextInt(1_900);
                var key = UUID.randomUUID().toString();
                var body = Map.of("sourceAccountId", account, "amount", amountMinor / 100.0, "currency", "EUR",
                        "recipient", Map.of("name", "Recipient " + i, "iban", "DE89370400440532013000"));
                var headers = Map.of("X-Owner-Id", owner.toString(), "Idempotency-Key", key);
                created.add(pool.submit(() -> {
                    // Every request is sent twice, as a client retrying after a timeout would.
                    var first = retries.submit(() -> post("transfer-service", "/transfers", headers, body));
                    var second = post("transfer-service", "/transfers", headers, body);
                    assertThat(first.get().get("id")).isEqualTo(second.get("id"));
                    return new Created(UUID.fromString(second.get("id").asText()), owner, amountMinor);
                }));
                if (i == TRANSFERS / 3) {
                    retries.submit(ChaosSystemTest::pauseKafkaBriefly);
                }
                if (i == TRANSFERS / 2) {
                    retries.submit(() -> restart("payout-worker"));
                }
            }
        }
        var transfers = new ArrayList<Created>();
        for (var f : created) {
            transfers.add(f.get());
        }
        System.out.printf("[system] %d transfers accepted in %d ms%n", transfers.size(), (System.nanoTime() - started) / 1_000_000);

        Map<UUID, String> finalStates = new ConcurrentHashMap<>();
        await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            for (var t : transfers) {
                if (!finalStates.containsKey(t.id())) {
                    var state = get("transfer-service", "/transfers/" + t.id(), Map.of("X-Owner-Id", t.owner().toString()))
                            .get("state").asText();
                    if (state.equals("COMPLETED") || state.equals("REFUNDED")) {
                        finalStates.put(t.id(), state);
                    }
                }
            }
            assertThat(finalStates).hasSize(transfers.size());
        });
        System.out.printf("[system] all final after %d ms: %s%n", (System.nanoTime() - started) / 1_000_000,
                finalStates.values().stream().collect(Collectors.groupingBy(s -> s, Collectors.counting())));

        // 1. Money conserved: customers lost exactly the completed amounts.
        long completedMinor = transfers.stream().filter(t -> finalStates.get(t.id()).equals("COMPLETED"))
                .mapToLong(Created::amountMinor).sum();
        long customerMinor = 0;
        for (var a : accounts) {
            customerMinor += Math.round(get("transfer-service", "/accounts/" + a, Map.of()).get("balance").get("amount").asDouble() * 100);
        }
        assertThat(customerMinor).isEqualTo(CUSTOMERS * TOP_UP_MINOR - completedMinor);

        // 2. At most one rail payment per transfer; settled exactly when completed.
        var statement = get("rails-simulator", "/statement", Map.of());
        Map<String, List<JsonNode>> byRef = new HashMap<>();
        statement.forEach(l -> byRef.computeIfAbsent(l.get("reference").asText(), k -> new ArrayList<>()).add(l));
        assertThat(byRef.values()).allSatisfy(lines -> assertThat(lines).hasSize(1));
        Set<String> settled = byRef.entrySet().stream().filter(e -> e.getValue().get(0).get("status").asText().equals("SETTLED"))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        Set<String> completed = finalStates.entrySet().stream().filter(e -> e.getValue().equals("COMPLETED"))
                .map(e -> e.getKey().toString()).collect(Collectors.toSet());
        assertThat(settled).isEqualTo(completed);

        // 3. The independent control agrees.
        var report = post("reconciliation-job", "/reconciliation/runs", Map.of(), Map.of());
        assertThat(report.get("breaks")).as(report.toPrettyString()).isEmpty();
        System.out.printf("[system] reconciliation clean: %s transfers, %s rail lines%n",
                report.get("transfersChecked"), report.get("railLinesChecked"));
    }

    // --- process management ---

    static void start(String service) {
        var env = new HashMap<String, String>();
        env.put("PORT", ports.get(service).toString());
        env.put("DB_USER", PG.getUsername());
        env.put("DB_PASSWORD", PG.getPassword());
        env.put("KAFKA_BOOTSTRAP", KAFKA.getBootstrapServers());
        env.put("TRACING_SAMPLING", "0");
        env.put("RAILS_URL", url("rails-simulator"));
        switch (service) {
            case "transfer-service" -> env.put("DB_URL", db("transfers"));
            case "payout-worker" -> {
                env.put("DB_URL", db("payouts"));
                env.put("CALLBACK_URL", url("payout-worker") + "/rails/callbacks");
                // Webhooks are lost while the worker is down; re-ask the rail soon instead of after 5 min.
                env.put("WISELITE_PAYOUT_CALLBACKTIMEOUT", "PT10S");
                env.put("WISELITE_PAYOUT_MAXBACKOFF", "PT10S");
            }
            case "reconciliation-job" -> {
                env.put("RECON_DB_URL", db("recon"));
                env.put("TRANSFERS_DB_URL", db("transfers"));
                env.put("PAYOUTS_DB_URL", db("payouts"));
                env.put("RECON_SCHEDULER_ENABLED", "false");
                env.put("WISELITE_RECON_GRACE", "PT0S");
            }
            default -> { }
        }
        var pb = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-Xmx384m", "-jar",
                System.getProperty("jar." + service))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(new File(LOGS, service + ".log")));
        pb.environment().putAll(env);
        try {
            processes.put(service, pb.start());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Void restart(String service) throws Exception {
        System.out.println("[chaos] SIGKILL " + service);
        processes.get(service).destroyForcibly().waitFor();
        Thread.sleep(2_000);
        start(service);
        awaitHealthy(service);
        System.out.println("[chaos] " + service + " back");
        return null;
    }

    static Void pauseKafkaBriefly() throws Exception {
        System.out.println("[chaos] pausing Kafka");
        var docker = KAFKA.getDockerClient();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        Thread.sleep(5_000);
        docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        System.out.println("[chaos] Kafka resumed");
        return null;
    }

    static void awaitHealthy(String service) {
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().until(() ->
                HTTP.send(HttpRequest.newBuilder(URI.create(url(service) + "/actuator/health")).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode() == 200);
    }

    // --- HTTP helpers ---

    static JsonNode post(String service, String path, Map<String, String> headers, Object body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url(service) + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        headers.forEach(req::header);
        return send(req.build());
    }

    static JsonNode get(String service, String path, Map<String, String> headers) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url(service) + path)).timeout(Duration.ofSeconds(30));
        headers.forEach(req::header);
        return send(req.build());
    }

    static JsonNode send(HttpRequest request) throws Exception {
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException(request.method() + " " + request.uri() + " -> " + response.statusCode() + " " + response.body());
        }
        return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }

    static String url(String service) {
        return "http://localhost:" + ports.get(service);
    }

    static String db(String name) {
        return PG.getJdbcUrl().replaceFirst("/" + PG.getDatabaseName() + "(\\?|$)", "/" + name + "$1");
    }

    static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
