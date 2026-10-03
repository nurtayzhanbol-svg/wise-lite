package com.wiselite.risk;

import com.wiselite.events.TransferStateChanged;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Event time = when the transfer changed state, not when the outbox relay got round to publishing it.
 * During a Kafka outage the relay publishes a backlog in seconds; with processing time that burst would
 * look like a velocity attack.
 */
public final class OccurredAtTimestampExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        if (record.value() instanceof TransferStateChanged e && e.occurredAt() != null) {
            return e.occurredAt().toEpochMilli();
        }
        return record.timestamp() >= 0 ? record.timestamp() : partitionTime;
    }
}
