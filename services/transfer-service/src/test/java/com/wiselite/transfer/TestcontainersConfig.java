package com.wiselite.transfer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Real Postgres and Kafka for integration tests. Postgres (not H2) because locking, isolation
 * and constraint behaviour are what we test; real Kafka because delivery semantics are.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }

    @Bean
    KafkaContainer kafka(DynamicPropertyRegistry properties) {
        var kafka = new KafkaContainer("apache/kafka:3.8.0");
        properties.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        return kafka;
    }
}
