package com.wiselite.outbox;

import com.wiselite.events.Topics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes outbox rows to Kafka, oldest first, and marks them published.
 *
 * <p>Delivery is <b>at-least-once</b>: if the process dies after Kafka acknowledged a record but
 * before this transaction commits, the row is still unpublished and is sent again. Consumers
 * dedupe by {@code event-id}.
 *
 * <p>Concurrency: {@code FOR UPDATE SKIP LOCKED} lets several relay instances run without
 * sending the same row twice concurrently. Per-transfer ordering is only guaranteed with a single
 * active relay (or relays partitioned by key); see ADR 0010.
 */
@Component
public class OutboxRelay {

    record Row(long seq, UUID eventId, String topic, String key, String type, String payload) {}

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final Duration sendTimeout;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka,
            @Value("${wiselite.outbox.relay.batch-size:100}") int batchSize,
            @Value("${wiselite.outbox.relay.send-timeout:10s}") Duration sendTimeout) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    /**
     * Publishes one batch. Returns the number of events published. If any send fails, the whole
     * batch stays unpublished and is retried later (some records may then be sent twice).
     */
    @Transactional
    public int publishBatch() {
        var rows = jdbc.sql("""
                        SELECT seq, event_id, topic, event_key, event_type, payload
                        FROM outbox_events
                        WHERE published_at IS NULL
                        ORDER BY seq
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED""")
                .param(batchSize)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)))
                .list();
        if (rows.isEmpty()) {
            return 0;
        }

        // Send the whole batch, then wait: the idempotent producer keeps per-partition order.
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>();
        for (var row : rows) {
            var record = new ProducerRecord<String, String>(row.topic(), row.key(), row.payload());
            record.headers().add(Topics.HEADER_EVENT_ID, row.eventId().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add(Topics.HEADER_EVENT_TYPE, row.type().getBytes(StandardCharsets.UTF_8));
            sends.add(kafka.send(record));
        }
        for (var send : sends) {
            try {
                send.get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OutboxPublishException(e);
            } catch (ExecutionException | TimeoutException e) {
                throw new OutboxPublishException(e);
            }
        }

        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE seq IN (:seqs)")
                .param("seqs", rows.stream().map(Row::seq).toList())
                .update();
        return rows.size();
    }

    public long unpublishedCount() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL").query(Long.class).single();
    }
}
