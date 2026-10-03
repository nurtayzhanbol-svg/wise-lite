package com.wiselite.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends an event to the outbox. {@code MANDATORY} propagation: calling this outside a
 * transaction is a bug (the event would no longer be atomic with the change it describes),
 * so Spring throws instead of silently opening a new transaction.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public OutboxWriter(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String key, String eventType, UUID eventId, Object payload) {
        jdbc.sql("INSERT INTO outbox_events (event_id, topic, event_key, event_type, payload) VALUES (?, ?, ?, ?, ?)")
                .params(eventId, topic, key, eventType, toJson(payload))
                .update();
    }

    private String toJson(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
