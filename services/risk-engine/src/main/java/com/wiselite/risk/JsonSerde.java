package com.wiselite.risk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON serde. Malformed input deserializes to {@code null} (and is filtered out) instead of killing the
 * stream thread: a poison pill must not stop risk scoring for everyone. A DLT would keep the bytes for analysis.
 */
public final class JsonSerde<T> implements Serde<T> {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final Logger log = LoggerFactory.getLogger(JsonSerde.class);

    private final Class<T> type;

    public JsonSerde(Class<T> type) {
        this.type = type;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, value) -> {
            if (value == null) {
                return null;
            }
            try {
                return MAPPER.writeValueAsBytes(value);
            } catch (JsonProcessingException e) {
                throw new SerializationException(e);
            }
        };
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, bytes) -> {
            if (bytes == null) {
                return null;
            }
            try {
                return MAPPER.readValue(bytes, type);
            } catch (IOException e) {
                log.warn("Skipping malformed {} on {}: {}", type.getSimpleName(), topic, e.getMessage());
                return null;
            }
        };
    }
}
