package com.wiselite.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.wiselite.events.RiskAlert;
import com.wiselite.events.Topics;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/** Real broker, exactly_once_v2, duplicates on the input topic. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RiskEngineIT {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        var dir = Files.createTempDirectory("risk-it").toString();
        r.add("wiselite.risk.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("wiselite.risk.state-dir", () -> dir);
    }

    @Autowired TestRestTemplate http;
    @Autowired RiskStreams streams;

    @Test
    void duplicatedBurstOnRealKafkaProducesExactlyOneCommittedAlert() throws Exception {
        await().atMost(Duration.ofSeconds(60)).until(() -> streams.health().getStatus().getCode().equals("UP"));
        var owner = UUID.randomUUID();
        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            for (var e : RiskTopologyTest.burst(owner, 6)) {
                for (int copy = 0; copy < 2; copy++) {
                    producer.send(new ProducerRecord<>(Topics.TRANSFER_EVENTS, e.transferId().toString(),
                            RiskTopologyTest.json(e)));
                }
            }
        }

        var received = new CopyOnWriteArrayList<RiskAlert>();
        var deserializer = new JsonSerde<>(RiskAlert.class).deserializer();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                // Only alerts from committed Streams transactions.
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(Topics.RISK_ALERTS));
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> received.add(deserializer.deserialize(r.topic(), r.value().getBytes())));
                assertThat(received).filteredOn(a -> a.subject().equals(owner.toString())).isNotEmpty();
            });
            for (int i = 0; i < 6; i++) { // and nothing more arrives
                consumer.poll(Duration.ofMillis(500)).forEach(r -> received.add(deserializer.deserialize(r.topic(), r.value().getBytes())));
            }
        }
        assertThat(received).filteredOn(a -> a.subject().equals(owner.toString()))
                .singleElement().extracting(RiskAlert::rule).isEqualTo(RiskAlert.VELOCITY);
        assertThat(http.getForObject("/actuator/health", String.class)).contains("UP");
    }
}
