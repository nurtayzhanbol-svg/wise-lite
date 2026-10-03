package com.wiselite.rails;

import static org.assertj.core.api.Assertions.assertThat;

import com.wiselite.rails.api.RailsApi;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatementIT {

    @Autowired TestRestTemplate http;

    @Test
    void statementListsEveryPaymentOnceWithItsAmount() {
        var key = UUID.randomUUID().toString();
        var headers = new HttpHeaders();
        headers.set(RailsApi.IDEMPOTENCY_KEY, key);
        var request = new RailsApi.PaymentRequest(key, 1_234, "EUR", "Bob", "DE89370400440532013000", "http://localhost:1/cb");
        http.postForEntity("/payments", new HttpEntity<>(request, headers), String.class);
        http.postForEntity("/payments", new HttpEntity<>(request, headers), String.class);

        var lines = http.getForObject("/statement", RailsApi.StatementLine[].class);

        assertThat(lines).filteredOn(l -> key.equals(l.reference())).singleElement()
                .satisfies(l -> {
                    assertThat(l.amountMinor()).isEqualTo(1_234);
                    assertThat(l.currency()).isEqualTo("EUR");
                    assertThat(l.createdAt()).isNotNull();
                });
    }
}
