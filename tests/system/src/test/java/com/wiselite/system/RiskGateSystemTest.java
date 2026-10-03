package com.wiselite.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The risk gate across real processes: transfer-service → outbox → Kafka → risk-engine (Kafka Streams) →
 * risk.decisions.v1 → transfer-service → outbox → payout-worker → rails-simulator.
 *
 * <p>The central assertion is on the rail's own statement: a HELD, BLOCKed or rejected transfer has no rail
 * payment, ever. Break the gate (e.g. let payout-worker act on FUNDED) and {@link #allowReviewBlockEndToEnd}
 * fails on "rail payments for HELD transfer".
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RiskGateSystemTest extends SystemHarness {

    static final long ALLOW = 10_00;
    static final long REVIEW = 400_00;
    static final long BLOCK = 700_00;
    static final String DECISIONS_TOPIC = "risk.decisions.v1";
    static final Map<String, String> OPERATOR = Map.of("X-Operator", "system-test");

    record Customer(UUID account, UUID owner) {}

    /**
     * Fail-closed timeout. Must exceed how long the decision consumer can be away: after a SIGKILL the dead
     * member keeps its partitions until the group session times out (45 s by default).
     */
    @Override
    protected Map<String, String> extraEnv(String service) {
        return service.equals("transfer-service") ? Map.of("RISK_DECISION_TIMEOUT", "PT60S") : Map.of();
    }

    @BeforeAll
    void railSettlesQuickly() throws Exception {
        post("rails-simulator", "/admin/faults", Map.of(), Map.of("settleDelayMs", 300));
    }

    @Test
    @Order(1)
    void allowReviewBlockEndToEnd() throws Exception {
        var c = customer(5_000_00);
        var allowed = transfer(c, ALLOW);
        var released = transfer(c, REVIEW);
        var rejected = transfer(c, REVIEW);
        var blocked = transfer(c, BLOCK);

        awaitState(c, allowed, "COMPLETED");
        awaitState(c, released, "HELD");
        awaitState(c, rejected, "HELD");
        awaitState(c, blocked, "REFUNDED");
        // Several rail settle delays and payout-worker polls: a leaked payout would be visible by now.
        Thread.sleep(3_000);
        assertThat(railPayments(allowed)).isEqualTo(1);
        for (var t : List.of(released, rejected, blocked)) {
            assertThat(railPayments(t)).as("rail payments for HELD/BLOCKed transfer %s", t).isZero();
            assertThat(payoutRows(t)).as("payout rows for HELD/BLOCKed transfer %s", t).isZero();
        }

        var releases = race(Collections.nCopies(8, (Callable<Integer>) () -> postStatus("transfer-service",
                "/internal/transfers/" + released + "/release", OPERATOR, Map.of("reason", "documents checked"))));
        assertThat(releases).containsOnly(200);
        awaitState(c, released, "COMPLETED");

        var rejects = race(Collections.nCopies(8, (Callable<Integer>) () -> postStatus("transfer-service",
                "/internal/transfers/" + rejected + "/reject", OPERATOR, Map.of("reason", "mule pattern"))));
        assertThat(rejects).containsOnly(200);
        awaitState(c, rejected, "REFUNDED");

        Thread.sleep(2_000);
        assertThat(railPayments(released)).as("8 releases → one rail payment").isEqualTo(1);
        assertThat(payoutRows(released)).isEqualTo(1);
        assertThat(railPayments(rejected)).isZero();
        assertThat(balance(c)).as("refunds happened exactly once").isEqualTo(5_000_00 - ALLOW - REVIEW);
    }

    /** Adversarial: operators click release and reject on the same transfer at the same time. */
    @Test
    @Order(2)
    void releaseRacingRejectHasExactlyOneWinner() throws Exception {
        var c = customer(5_000_00);
        var held = new ArrayList<UUID>();
        for (int i = 0; i < 6; i++) {
            held.add(transfer(c, REVIEW));
        }
        for (var t : held) {
            awaitState(c, t, "HELD");
        }
        long completed = 0;
        for (var t : held) {
            var tasks = new ArrayList<Callable<String>>();
            for (int k = 0; k < 4; k++) {
                tasks.add(() -> "release:" + postStatus("transfer-service", "/internal/transfers/" + t + "/release", OPERATOR, Map.of()));
                tasks.add(() -> "reject:" + postStatus("transfer-service", "/internal/transfers/" + t + "/reject", OPERATOR, Map.of()));
            }
            var results = race(tasks);
            boolean releaseWon = results.contains("release:200");
            assertThat(releaseWon && results.contains("reject:200")).as("both verdicts applied: %s", results).isFalse();
            assertThat(results).filteredOn(r -> r.startsWith(releaseWon ? "reject" : "release")).containsOnly(
                    releaseWon ? "reject:409" : "release:409");
            awaitState(c, t, releaseWon ? "COMPLETED" : "REFUNDED");
            completed += releaseWon ? 1 : 0;
        }
        Thread.sleep(2_000);
        for (var t : held) {
            var state = state(c, t);
            assertThat(railPayments(t)).as(t + " " + state).isEqualTo(state.equals("COMPLETED") ? 1 : 0);
        }
        assertThat(balance(c)).isEqualTo(5_000_00 - completed * REVIEW);
    }

    /**
     * Kafka redelivery at scale: every decision ever published is published again, plus conflicting decisions
     * (new ids) for a completed, a refunded and a held transfer. Nothing may change.
     */
    @Test
    @Order(3)
    void replayedAndLateDecisionsChangeNothing() throws Exception {
        var c = customer(5_000_00);
        var completed = transfer(c, ALLOW);
        var refunded = transfer(c, BLOCK);
        var held = transfer(c, REVIEW);
        awaitState(c, completed, "COMPLETED");
        awaitState(c, refunded, "REFUNDED");
        awaitState(c, held, "HELD");
        var balanceBefore = balance(c);
        var railBefore = get("rails-simulator", "/statement", Map.of()).size();

        var all = readAll(DECISIONS_TOPIC);
        assertThat(all).hasSizeGreaterThan(10);
        try (var producer = producer()) {
            for (var r : all) {
                producer.send(new ProducerRecord<>(DECISIONS_TOPIC, r.key(), r.value()));
            }
            // Same key → same partition, behind the replays: once these are recorded, the replays were consumed.
            producer.send(late(completed, "BLOCK"));
            producer.send(late(refunded, "ALLOW"));
            producer.send(late(held, "ALLOW"));
            producer.flush();
        }
        System.out.printf("[risk] replayed %d decisions + 3 conflicting%n", all.size());

        for (var t : List.of(completed, refunded, held)) {
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).until(() ->
                    hasDecision(t, "ENGINE", "IGNORED"));
        }
        Thread.sleep(3_000);
        assertThat(state(c, completed)).isEqualTo("COMPLETED");
        assertThat(state(c, refunded)).as("late ALLOW must not resurrect a refunded transfer").isEqualTo("REFUNDED");
        assertThat(state(c, held)).as("late ALLOW must not release a HELD transfer").isEqualTo("HELD");
        assertThat(payoutRows(held)).isZero();
        assertThat(railPayments(refunded)).isZero();
        assertThat(balance(c)).isEqualTo(balanceBefore);
        assertThat(get("rails-simulator", "/statement", Map.of()).size()).as("no new rail payments").isEqualTo(railBefore);
    }

    /** Fail closed: no decision → HELD, never paid; the late decision is recorded but does not release it. */
    @Test
    @Order(4)
    void riskEngineDownFailsClosed() throws Exception {
        kill("risk-engine");
        var c = customer(1_000_00);
        var t = transfer(c, ALLOW);

        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(2)).until(() -> state(c, t).equals("HELD"));
        assertThat(hasDecision(t, "TIMEOUT", "APPLIED")).isTrue();
        assertThat(payoutRows(t)).isZero();

        start("risk-engine");
        awaitHealthy("risk-engine");
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(2)).until(() -> hasDecision(t, "ENGINE", "IGNORED"));
        assertThat(state(c, t)).isEqualTo("HELD");
        assertThat(payoutRows(t)).isZero();
        assertThat(railPayments(t)).isZero();

        post("transfer-service", "/internal/transfers/" + t + "/release", OPERATOR, Map.of("reason", "engine was down"));
        awaitState(c, t, "COMPLETED");
        assertThat(railPayments(t)).isEqualTo(1);
    }

    /** transfer-service is SIGKILLed while decisions are in flight, then payout-worker too. */
    @Test
    @Order(5)
    void crashesDuringDecisionProcessingKeepEffectsExactlyOnce() throws Exception {
        var c = customer(10_000_00);
        var tasks = new ArrayList<Callable<UUID>>();
        for (int i = 0; i < 30; i++) {
            long amount = i % 5 == 0 ? REVIEW : ALLOW + i;
            tasks.add(() -> transfer(c, amount));
        }
        var ids = race(tasks);
        restart("transfer-service");
        restart("payout-worker");

        Map<UUID, String> expected = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            expected.put(ids.get(i), i % 5 == 0 ? "HELD" : "COMPLETED");
        }
        for (var e : expected.entrySet()) {
            awaitState(c, e.getKey(), e.getValue());
        }
        Thread.sleep(2_000);
        for (var e : expected.entrySet()) {
            assertThat(railPayments(e.getKey())).as(e.toString()).isEqualTo(e.getValue().equals("COMPLETED") ? 1 : 0);
            assertThat(count("transfers", "SELECT count(*) FROM risk_decisions WHERE transfer_id = ? AND outcome = 'APPLIED'",
                    e.getKey())).isEqualTo(1);
        }
    }

    @Test
    @Order(6)
    void booksReconcile() throws Exception {
        var report = post("reconciliation-job", "/reconciliation/runs", Map.of(), Map.of());
        assertThat(report.get("breaks")).as(report.toPrettyString()).isEmpty();
    }

    // --- helpers ---

    Customer customer(long topUpMinor) throws Exception {
        var owner = UUID.randomUUID();
        var account = UUID.fromString(post("transfer-service", "/accounts", Map.of(),
                Map.of("ownerId", owner, "currency", "EUR")).get("id").asText());
        post("transfer-service", "/internal/accounts/" + account + "/top-ups", Map.of("Idempotency-Key", UUID.randomUUID().toString()),
                Map.of("amount", topUpMinor / 100, "currency", "EUR"));
        return new Customer(account, owner);
    }

    UUID transfer(Customer c, long amountMinor) throws Exception {
        var body = Map.of("sourceAccountId", c.account(), "amount", amountMinor / 100.0, "currency", "EUR",
                "recipient", Map.of("name", "Recipient", "iban", "DE89370400440532013000"));
        return UUID.fromString(post("transfer-service", "/transfers",
                Map.of("X-Owner-Id", c.owner().toString(), "Idempotency-Key", UUID.randomUUID().toString()), body).get("id").asText());
    }

    String state(Customer c, UUID id) throws Exception {
        return get("transfer-service", "/transfers/" + id, Map.of("X-Owner-Id", c.owner().toString())).get("state").asText();
    }

    void awaitState(Customer c, UUID id, String expected) {
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                .untilAsserted(() -> assertThat(state(c, id)).as(id.toString()).isEqualTo(expected));
    }

    long balance(Customer c) throws Exception {
        return Math.round(get("transfer-service", "/accounts/" + c.account(), Map.of()).get("balance").get("amount").asDouble() * 100);
    }

    long railPayments(UUID transferId) throws Exception {
        long n = 0;
        for (JsonNode line : get("rails-simulator", "/statement", Map.of())) {
            if (line.get("reference").asText().equals(transferId.toString())) {
                n++;
            }
        }
        return n;
    }

    long payoutRows(UUID transferId) throws Exception {
        return count("payouts", "SELECT count(*) FROM payouts WHERE transfer_id = ?", transferId);
    }

    boolean hasDecision(UUID transferId, String source, String outcome) throws Exception {
        for (var d : get("transfer-service", "/internal/transfers/" + transferId + "/risk-decisions", Map.of())) {
            if (d.get("source").asText().equals(source) && d.get("outcome").asText().equals(outcome)) {
                return true;
            }
        }
        return false;
    }

    ProducerRecord<String, String> late(UUID transferId, String decision) throws Exception {
        var value = JSON.writeValueAsString(Map.of("decisionId", UUID.randomUUID(), "transferId", transferId,
                "decision", decision, "reasons", List.of("LATE_RERUN"), "rulesVersion", "replay", "decidedAt", "2026-10-03T12:00:00Z"));
        return new ProducerRecord<>(DECISIONS_TOPIC, transferId.toString(), value);
    }

    List<ConsumerRecord<String, String>> readAll(String topic) {
        var props = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "replayer-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        var records = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            int emptyPolls = 0;
            while (emptyPolls < 6) {
                var batch = consumer.poll(Duration.ofMillis(500));
                if (batch.isEmpty() && !records.isEmpty()) {
                    emptyPolls++;
                }
                batch.forEach(records::add);
            }
        }
        return records;
    }

    KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()),
                new StringSerializer(), new StringSerializer());
    }
}
