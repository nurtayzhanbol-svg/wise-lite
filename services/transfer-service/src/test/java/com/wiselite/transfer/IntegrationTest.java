package com.wiselite.transfer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Same configuration for every integration test, so Spring caches one context and one Postgres container. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Tests drive the relay explicitly via OutboxRelay.publishBatch().
            "wiselite.outbox.relay.enabled=false",
            // Fail fast when a test makes Kafka unavailable.
            "spring.kafka.producer.properties.delivery.timeout.ms=3000",
            "spring.kafka.producer.properties.request.timeout.ms=1000",
            "spring.kafka.producer.properties.max.block.ms=2000"
        })
@Import(TestcontainersConfig.class)
public @interface IntegrationTest {}
