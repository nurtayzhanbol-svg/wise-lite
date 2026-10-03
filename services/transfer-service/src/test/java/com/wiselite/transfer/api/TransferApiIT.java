package com.wiselite.transfer.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiselite.transfer.IntegrationTest;
import com.wiselite.transfer.ledger.AccountService;
import com.wiselite.transfer.ledger.LedgerRepository;
import com.wiselite.transfer.ledger.Money;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

@IntegrationTest
class TransferApiIT {

    @Autowired TestRestTemplate http;
    @Autowired AccountService accounts;
    @Autowired LedgerRepository ledger;
    @Autowired ObjectMapper json;

    @Test
    void retryWithSameKeyReturnsTheSameTransferAndDebitsOnce() throws Exception {
        var owner = UUID.randomUUID();
        var account = fundedAccount(owner, "100.00");
        var key = UUID.randomUUID().toString();

        var first = postTransfer(owner, key, body(account, "30.00"));
        var second = postTransfer(owner, key, body(account, "30.00"));

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getHeaders().getFirst(TransferController.REPLAYED_HEADER)).isEqualTo("false");
        assertThat(second.getHeaders().getFirst(TransferController.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(accounts.balance(account)).isEqualTo(Money.of("70.00", "EUR"));
    }

    @Test
    void equivalentAmountFormatsCountAsTheSameRequest() {
        var owner = UUID.randomUUID();
        var account = fundedAccount(owner, "100.00");
        var key = UUID.randomUUID().toString();

        postTransfer(owner, key, body(account, "30"));
        var second = postTransfer(owner, key, body(account, "30.00"));

        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(accounts.balance(account)).isEqualTo(Money.of("70.00", "EUR"));
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        var owner = UUID.randomUUID();
        var account = fundedAccount(owner, "100.00");
        var key = UUID.randomUUID().toString();

        postTransfer(owner, key, body(account, "30.00"));
        var conflicting = postTransfer(owner, key, body(account, "31.00"));

        assertThat(conflicting.getStatusCode().value()).isEqualTo(422);
        assertThat(accounts.balance(account)).isEqualTo(Money.of("70.00", "EUR"));
    }

    @Test
    void keysAreScopedPerOwner() {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        var aliceAccount = fundedAccount(alice, "100.00");
        var bobAccount = fundedAccount(bob, "100.00");
        var key = "shared-key-" + UUID.randomUUID();

        assertThat(postTransfer(alice, key, body(aliceAccount, "10.00")).getStatusCode().value()).isEqualTo(201);
        assertThat(postTransfer(bob, key, body(bobAccount, "20.00")).getStatusCode().value()).isEqualTo(201);
        assertThat(accounts.balance(bobAccount)).isEqualTo(Money.of("80.00", "EUR"));
    }

    @Test
    void missingKeyIsABadRequest() {
        var owner = UUID.randomUUID();
        var account = fundedAccount(owner, "100.00");
        var headers = new HttpHeaders();
        headers.set("X-Owner-Id", owner.toString());

        var response = http.postForEntity("/transfers", new HttpEntity<>(body(account, "1.00"), headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(accounts.balance(account)).isEqualTo(Money.of("100.00", "EUR"));
    }

    @Test
    void failedRequestIsNotStoredSoTheSameKeyCanBeRetriedLater() {
        var owner = UUID.randomUUID();
        var account = fundedAccount(owner, "10.00");
        var key = UUID.randomUUID().toString();

        assertThat(postTransfer(owner, key, body(account, "50.00")).getStatusCode().value()).isEqualTo(422);
        topUp(account, "40.00");
        assertThat(postTransfer(owner, key, body(account, "50.00")).getStatusCode().value()).isEqualTo(201);
        assertThat(accounts.balance(account)).isEqualTo(Money.of("0.00", "EUR"));
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateExactlyOneTransfer() throws Exception {
        var owner = UUID.randomUUID();
        var account = fundedAccount(owner, "100.00");
        var key = UUID.randomUUID().toString();
        int threads = 16;
        var start = new CountDownLatch(1);
        var tasks = new ArrayList<Callable<ResponseEntity<String>>>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                start.await();
                return postTransfer(owner, key, body(account, "10.00"));
            });
        }

        List<ResponseEntity<String>> responses = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (var f : futures) {
                responses.add(f.get());
            }
        }

        var ids = new HashSet<String>();
        for (var r : responses) {
            assertThat(r.getStatusCode().value()).isEqualTo(201);
            ids.add(read(r).get("id").asText());
        }
        assertThat(ids).hasSize(1);
        assertThat(ledger.countJournalEntries("transfer:" + ids.iterator().next() + ":fund")).isEqualTo(1);
        assertThat(accounts.balance(account)).isEqualTo(Money.of("90.00", "EUR"));
    }

    @Test
    void transferIsInvisibleToOtherOwners() throws Exception {
        var owner = UUID.randomUUID();
        var account = fundedAccount(owner, "100.00");
        var id = read(postTransfer(owner, UUID.randomUUID().toString(), body(account, "5.00"))).get("id").asText();

        var headers = new HttpHeaders();
        headers.set("X-Owner-Id", UUID.randomUUID().toString());
        var response = http.exchange("/transfers/" + id, org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void lifecycleThroughInternalEndpoints() throws Exception {
        var owner = UUID.randomUUID();
        var account = fundedAccount(owner, "100.00");
        var id = read(postTransfer(owner, UUID.randomUUID().toString(), body(account, "5.00"))).get("id").asText();

        assertThat(http.postForEntity("/internal/transfers/" + id + "/complete", null, String.class).getStatusCode().value()).isEqualTo(409);
        http.postForEntity("/internal/transfers/" + id + "/processing", null, String.class);
        var failed = http.postForEntity("/internal/transfers/" + id + "/fail", Map.of("reason", "timeout"), String.class);

        assertThat(read(failed).get("state").asText()).isEqualTo("REFUNDED");
        assertThat(read(failed).get("history")).hasSize(5);
        assertThat(accounts.balance(account)).isEqualTo(Money.of("100.00", "EUR"));
    }

    private ResponseEntity<String> postTransfer(UUID owner, String key, Map<String, Object> body) {
        var headers = new HttpHeaders();
        headers.set("X-Owner-Id", owner.toString());
        headers.set("Idempotency-Key", key);
        return http.postForEntity("/transfers", new HttpEntity<>(body, headers), String.class);
    }

    private static Map<String, Object> body(UUID account, String amount) {
        return Map.of("sourceAccountId", account, "amount", new java.math.BigDecimal(amount), "currency", "EUR",
                "recipient", Map.of("name", "Bob", "iban", "DE89 3704 0044 0532 0130 00"));
    }

    private UUID fundedAccount(UUID owner, String amount) {
        var account = accounts.openCustomerAccount(owner, Currency.getInstance("EUR")).id();
        topUp(account, amount);
        return account;
    }

    private void topUp(UUID account, String amount) {
        var headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var r = http.postForEntity("/internal/accounts/" + account + "/top-ups",
                new HttpEntity<>(Map.of("amount", new java.math.BigDecimal(amount), "currency", "EUR"), headers), String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(201);
    }

    private JsonNode read(ResponseEntity<String> r) throws Exception {
        return json.readTree(r.getBody());
    }
}
