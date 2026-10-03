package com.wiselite.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainer.class)
class TransferServiceApplicationIT {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void healthEndpointIsUp() {
        var body = http.getForObject("/actuator/health", String.class);
        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    void flywayMigrationsApplied() {
        var description = jdbc.queryForObject("SELECT description FROM schema_info WHERE id = 1", String.class);
        assertThat(description).isEqualTo("wise-lite transfer-service baseline");
    }
}
