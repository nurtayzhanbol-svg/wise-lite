package com.wiselite.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.junit.jupiter.api.Test;

/**
 * The whole system, as separate processes, under concurrent load while things break:
 * rail fails before/after processing, rejects, duplicates webhooks; Kafka is paused;
 * payout-worker is killed with SIGKILL and restarted. Afterwards every transfer must be final,
 * money conserved, the rail must hold at most one payment per transfer, and reconciliation must be clean.
 * Every transfer goes through the risk gate (amounts are below the review limit, so all are ALLOWed).
 */
class ChaosSystemTest extends SystemHarness {

    static final int CUSTOMERS = 10;
    static final int TRANSFERS = 200;
    static final long TOP_UP_MINOR = 1_000_00;

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
                    retries.submit(this::pauseKafkaBriefly);
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

}
