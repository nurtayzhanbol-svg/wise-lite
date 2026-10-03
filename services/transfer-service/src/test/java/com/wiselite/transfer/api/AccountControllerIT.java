package com.wiselite.transfer.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.wiselite.transfer.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

@IntegrationTest
class AccountControllerIT {

    @Autowired
    TestRestTemplate http;

    @Test
    void openAccountAndReadItsZeroBalance() {
        var owner = UUID.randomUUID();
        var created = http.postForEntity("/accounts", Map.of("ownerId", owner, "currency", "EUR"), AccountController.AccountView.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var id = created.getBody().id();
        var fetched = http.getForObject("/accounts/" + id, AccountController.AccountView.class);
        assertThat(fetched.balance().amount()).isEqualByComparingTo("0");
        assertThat(fetched.balance().currency()).isEqualTo("EUR");
        assertThat(http.getForObject("/owners/" + owner + "/accounts", AccountController.AccountView[].class)).hasSize(1);
    }

    @Test
    void secondAccountInTheSameCurrencyIsAConflict() {
        var owner = UUID.randomUUID();
        http.postForEntity("/accounts", Map.of("ownerId", owner, "currency", "EUR"), String.class);
        var second = http.postForEntity("/accounts", Map.of("ownerId", owner, "currency", "EUR"), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void unknownAccountIs404() {
        var response = http.getForEntity("/accounts/" + UUID.randomUUID(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
