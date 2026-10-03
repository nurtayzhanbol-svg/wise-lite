package com.wiselite.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiselite.events.Topics;
import com.wiselite.events.TransferStateChanged;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest
@Import(TestcontainersConfig.class)
class PayoutWorkerIT {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired KafkaContainer kafkaContainer;
    @SpyBean PayoutService payouts;

    @Test
    void fundedEventCreatesOnePayout() throws Exception {
        var event = funded(UUID.randomUUID());

        send(event);

        await().atMost(Duration.ofSeconds(20)).until(() -> payouts.payoutCount(event.transferId()) == 1);
    }

    @Test
    void redeliveredEventIsAppliedOnce() throws Exception {
        var event = funded(UUID.randomUUID());
        var marker = funded(UUID.randomUUID());

        send(event);
        send(event);
        send(event);
        send(marker); // same key order is per partition; wait for a later record to be sure duplicates were seen
        await().atMost(Duration.ofSeconds(20)).until(() -> payouts.payoutCount(marker.transferId()) == 1
                && processed(event.eventId()));

        assertThat(payouts.payoutCount(event.transferId())).isEqualTo(1);
    }

    @Test
    void twoDifferentEventsForTheSameTransferStillPayOutOnce() throws Exception {
        var transferId = UUID.randomUUID();
        var first = funded(transferId);
        var second = funded(transferId); // new event id, same transfer: e.g. a buggy producer

        send(first);
        send(second);
        await().atMost(Duration.ofSeconds(20)).until(() -> processed(first.eventId()) && processed(second.eventId()));

        assertThat(payouts.payoutCount(transferId)).isEqualTo(1);
    }

    @Test
    void nonFundedEventsAreIgnored() throws Exception {
        var transferId = UUID.randomUUID();
        send(event(transferId, "FUNDED", "PROCESSING"));
        var marker = funded(transferId);
        send(marker);

        await().atMost(Duration.ofSeconds(20)).until(() -> payouts.payoutCount(transferId) == 1);
    }

    @Test
    void poisonRecordGoesToTheDltAndDoesNotBlockThePartition() throws Exception {
        var key = UUID.randomUUID();
        kafka.send(Topics.TRANSFER_EVENTS, key.toString(), "{not json").get();
        var after = funded(key); // same key -> same partition, right behind the poison record
        send(after);

        await().atMost(Duration.ofSeconds(20)).until(() -> payouts.payoutCount(key) == 1);
        assertThat(dltValues(key)).containsExactly("{not json");
    }

    @Test
    void transientFailuresAreRetriedAndTheEffectHappensOnce() throws Exception {
        var event = funded(UUID.randomUUID());
        doThrow(new TransientDataAccessResourceException("db blip"))
                .doThrow(new TransientDataAccessResourceException("db blip"))
                .doCallRealMethod()
                .when(payouts).handle(any());

        send(event);

        await().atMost(Duration.ofSeconds(20)).until(() -> payouts.payoutCount(event.transferId()) == 1);
        doCallRealMethod().when(payouts).handle(any());
    }

    private boolean processed(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = ?").param(eventId).query(Long.class).single() == 1;
    }

    private List<String> dltValues(UUID key) {
        var props = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaConsumerConfig.DLT));
            var deadline = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(deadline)) {
                var values = new java.util.ArrayList<String>();
                for (var r : consumer.poll(Duration.ofMillis(500))) {
                    if (key.toString().equals(r.key())) {
                        values.add(r.value());
                    }
                }
                if (!values.isEmpty()) {
                    return values;
                }
            }
        }
        return List.of();
    }

    private void send(TransferStateChanged event) throws Exception {
        kafka.send(Topics.TRANSFER_EVENTS, event.transferId().toString(), json.writeValueAsString(event)).get();
    }

    private static TransferStateChanged funded(UUID transferId) {
        return event(transferId, "CREATED", "FUNDED");
    }

    private static TransferStateChanged event(UUID transferId, String from, String to) {
        return new TransferStateChanged(UUID.randomUUID(), transferId, UUID.randomUUID(), from, to, 2_500, "EUR",
                "Bob", "DE89370400440532013000", null, Instant.now());
    }
}
