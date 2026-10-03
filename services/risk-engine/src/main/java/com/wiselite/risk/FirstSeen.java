package com.wiselite.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.WindowStore;

/**
 * Forwards a record only if its id was not seen within {@code period} (event time) of this record.
 * Used twice: to drop duplicate events (id = eventId) and to alert once per burst (id = rule + subject).
 * The store is local to the task, so all copies of an id must arrive on the same partition: true for
 * events (key = transferId) and for alerts (keyed by subject after the aggregation's repartition).
 */
final class FirstSeen<V> implements Processor<String, V, String, V> {

    private final String storeName;
    private final Duration period;
    private final Function<V, String> id;
    private ProcessorContext<String, V> context;
    private WindowStore<String, Long> seen;

    FirstSeen(String storeName, Duration period, Function<V, String> id) {
        this.storeName = storeName;
        this.period = period;
        this.id = id;
    }

    @Override
    public void init(ProcessorContext<String, V> context) {
        this.context = context;
        this.seen = context.getStateStore(storeName);
    }

    @Override
    public void process(Record<String, V> record) {
        var key = id.apply(record.value());
        var at = Instant.ofEpochMilli(record.timestamp());
        try (var earlier = seen.fetch(key, at.minus(period), at.plus(period))) {
            if (earlier.hasNext()) {
                return;
            }
        }
        seen.put(key, record.timestamp(), record.timestamp());
        context.forward(record);
    }
}
