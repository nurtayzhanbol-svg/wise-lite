package com.wiselite.system;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Starts the whole system for one test class: Postgres and Kafka in containers, every service as its own
 * boot-jar OS process (so SIGKILL is a real crash). Logs go to build/system-test-logs/&lt;TestClass&gt;/.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SystemHarness {

    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static final List<String> SERVICES =
            List.of("transfer-service", "payout-worker", "rails-simulator", "reconciliation-job", "risk-engine");

    /** Risk limits for every system test: chaos traffic (≤ 19.99 EUR) is always ALLOWed. */
    static final long REVIEW_LIMIT_MINOR = 300_00;
    static final long BLOCK_LIMIT_MINOR = 600_00;

    final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");
    final File logs = new File("build/system-test-logs/" + getClass().getSimpleName());
    final Map<String, Process> processes = new ConcurrentHashMap<>();
    final Map<String, Integer> ports = new LinkedHashMap<>();

    /** Per-class environment overrides. */
    protected Map<String, String> extraEnv(String service) {
        return Map.of();
    }

    @BeforeAll
    void startSystem() throws Exception {
        if (logs.exists()) {
            for (var f : logs.listFiles()) {
                f.delete();
            }
        }
        logs.mkdirs();
        pg.start();
        kafka.start();
        try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); var st = c.createStatement()) {
            for (var db : List.of("transfers", "payouts", "recon")) {
                st.execute("CREATE DATABASE " + db);
            }
        }
        for (var s : SERVICES) {
            ports.put(s, freePort());
        }
        for (var s : SERVICES) {
            start(s);
        }
        for (var s : SERVICES) {
            awaitHealthy(s);
        }
    }

    @AfterAll
    void stopSystem() {
        processes.values().forEach(Process::destroyForcibly);
        kafka.stop();
        pg.stop();
    }

    // --- process management ---

    void start(String service) {
        var env = new HashMap<String, String>();
        env.put("PORT", ports.get(service).toString());
        env.put("DB_USER", pg.getUsername());
        env.put("DB_PASSWORD", pg.getPassword());
        env.put("KAFKA_BOOTSTRAP", kafka.getBootstrapServers());
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
            case "risk-engine" -> {
                env.put("RISK_STATE_DIR", new File(logs, "risk-state").getAbsolutePath());
                env.put("WISELITE_RISK_RULES_REVIEWAMOUNTMINOR", String.valueOf(REVIEW_LIMIT_MINOR));
                env.put("WISELITE_RISK_RULES_BLOCKAMOUNTMINOR", String.valueOf(BLOCK_LIMIT_MINOR));
                // Load tests send 20 transfers per customer per minute; velocity/volume are unit-tested instead.
                env.put("WISELITE_RISK_RULES_MAXTRANSFERSPERWINDOW", "1000000");
                env.put("WISELITE_RISK_RULES_MAXVOLUMEMINOR", "1000000000000");
            }
            default -> { }
        }
        env.putAll(extraEnv(service));
        var pb = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-Xmx384m", "-jar",
                System.getProperty("jar." + service))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logs, service + ".log")));
        pb.environment().putAll(env);
        try {
            processes.put(service, pb.start());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    void kill(String service) throws InterruptedException {
        System.out.println("[chaos] SIGKILL " + service);
        processes.get(service).destroyForcibly().waitFor();
    }

    Void restart(String service) throws Exception {
        kill(service);
        Thread.sleep(2_000);
        start(service);
        awaitHealthy(service);
        System.out.println("[chaos] " + service + " back");
        return null;
    }

    Void pauseKafkaBriefly() throws Exception {
        System.out.println("[chaos] pausing Kafka");
        var docker = kafka.getDockerClient();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        Thread.sleep(5_000);
        docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        System.out.println("[chaos] Kafka resumed");
        return null;
    }

    void awaitHealthy(String service) {
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().until(() ->
                HTTP.send(HttpRequest.newBuilder(URI.create(url(service) + "/actuator/health")).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode() == 200);
    }

    /** Runs all tasks at the same instant (as far as a latch allows) and returns their results in order. */
    static <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(tasks.size())) {
            var futures = new ArrayList<Future<T>>();
            for (var task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            var results = new ArrayList<T>();
            for (var f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }

    // --- HTTP / DB helpers ---

    JsonNode post(String service, String path, Map<String, String> headers, Object body) throws Exception {
        return send(postRequest(service, path, headers, body));
    }

    int postStatus(String service, String path, Map<String, String> headers, Object body) throws Exception {
        return HTTP.send(postRequest(service, path, headers, body), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private HttpRequest postRequest(String service, String path, Map<String, String> headers, Object body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url(service) + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        headers.forEach(req::header);
        return req.build();
    }

    JsonNode get(String service, String path, Map<String, String> headers) throws Exception {
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

    long count(String database, String sql, UUID param) throws Exception {
        try (var c = DriverManager.getConnection(db(database), pg.getUsername(), pg.getPassword());
                var st = c.prepareStatement(sql)) {
            st.setObject(1, param);
            try (var rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    String url(String service) {
        return "http://localhost:" + ports.get(service);
    }

    String db(String name) {
        return pg.getJdbcUrl().replaceFirst("/" + pg.getDatabaseName() + "(\\?|$)", "/" + name + "$1");
    }

    static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
