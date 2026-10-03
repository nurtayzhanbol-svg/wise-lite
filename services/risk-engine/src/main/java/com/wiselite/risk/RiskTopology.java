package com.wiselite.risk;

import com.wiselite.events.RiskAlert;
import com.wiselite.events.Topics;
import com.wiselite.events.TransferStateChanged;
import io.micrometer.core.instrument.Metrics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.SlidingWindows;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Window;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;

/**
 * transfers.events.v1 → (FUNDED only, deduplicated by eventId, event time) → three windowed rules → risk.alerts.v1.
 *
 * <ul>
 *   <li>VELOCITY: more than N funded transfers by one owner within a sliding window.
 *   <li>DAILY_VOLUME: owner's funded amount per currency in a tumbling day exceeds a limit.
 *   <li>MULE_RECIPIENT: more than K distinct owners pay one IBAN within a sliding window (fan-in).
 * </ul>
 */
public final class RiskTopology {

    static final String DEDUPE_STORE = "dedupe-event-ids";
    static final String SUPPRESS_VELOCITY = "suppress-velocity";
    static final String SUPPRESS_VOLUME = "suppress-volume";
    static final String SUPPRESS_MULE = "suppress-mule";

    private RiskTopology() {}

    public static Topology build(RiskRules rules) {
        var builder = new StreamsBuilder();
        var events = new JsonSerde<>(TransferStateChanged.class);
        var alerts = new JsonSerde<>(RiskAlert.class);

        builder.addStateStore(seenStore(DEDUPE_STORE, rules.dedupeRetention()));
        builder.addStateStore(seenStore(SUPPRESS_VELOCITY, rules.velocityWindow()));
        builder.addStateStore(seenStore(SUPPRESS_VOLUME, rules.volumeWindow()));
        builder.addStateStore(seenStore(SUPPRESS_MULE, rules.recipientWindow()));

        KStream<String, TransferStateChanged> funded = builder
                .stream(Topics.TRANSFER_EVENTS, Consumed.with(Serdes.String(), events)
                        .withTimestampExtractor(new OccurredAtTimestampExtractor()).withName("transfer-events"))
                .filter((k, e) -> e != null && "FUNDED".equals(e.toState()), Named.as("only-funded"))
                // Exactly-once in Kafka Streams covers read→process→write; the outbox can still publish the
                // same event twice (ADR 0010). Only an id-based dedupe makes the counts right.
                .process(() -> new FirstSeen<TransferStateChanged>(DEDUPE_STORE, rules.dedupeRetention(),
                        e -> e.eventId().toString()), Named.as("dedupe-event-id"), DEDUPE_STORE);

        var velocity = funded
                .groupBy((k, e) -> e.ownerId().toString(), Grouped.with("by-owner", Serdes.String(), events))
                .windowedBy(SlidingWindows.ofTimeDifferenceAndGrace(rules.velocityWindow(), rules.grace()))
                .aggregate(Agg::empty, (k, e, agg) -> agg.add(e), Named.as("velocity-agg"), store("velocity"))
                .toStream(Named.as("velocity-updates"))
                .filter((w, agg) -> agg != null && agg.count() > rules.maxTransfersPerWindow())
                .map((w, agg) -> alert(RiskAlert.VELOCITY, w,
                        agg.count() + " funded transfers within " + rules.velocityWindow(), agg));
        emit(velocity, SUPPRESS_VELOCITY, rules.velocityWindow(), RiskAlert::subject, alerts);

        var volume = funded
                .groupBy((k, e) -> e.ownerId() + "|" + e.currency(),
                        Grouped.with("by-owner-currency", Serdes.String(), events))
                .windowedBy(TimeWindows.ofSizeAndGrace(rules.volumeWindow(), rules.grace()))
                .aggregate(Agg::empty, (k, e, agg) -> agg.add(e), Named.as("volume-agg"), store("volume"))
                .toStream(Named.as("volume-updates"))
                .filter((w, agg) -> agg != null && agg.sumMinor() > rules.maxVolumeMinor())
                .map((w, agg) -> alert(RiskAlert.DAILY_VOLUME, w,
                        agg.sumMinor() + " minor units funded in " + rules.volumeWindow(), agg));
        // Tumbling windows: one alert per subject per window.
        emit(volume, SUPPRESS_VOLUME, rules.volumeWindow(), a -> a.subject() + "|" + a.windowStart(), alerts);

        var mule = funded
                .groupBy((k, e) -> e.recipientIban().replace(" ", "").toUpperCase(Locale.ROOT),
                        Grouped.with("by-recipient", Serdes.String(), events))
                .windowedBy(SlidingWindows.ofTimeDifferenceAndGrace(rules.recipientWindow(), rules.grace()))
                .aggregate(Agg::empty, (k, e, agg) -> agg.add(e), Named.as("recipient-agg"), store("recipient"))
                .toStream(Named.as("recipient-updates"))
                .filter((w, agg) -> agg != null && agg.owners().size() > rules.maxSendersPerRecipient())
                .map((w, agg) -> alert(RiskAlert.MULE_RECIPIENT, w,
                        agg.owners().size() + " distinct senders within " + rules.recipientWindow(), agg));
        emit(mule, SUPPRESS_MULE, rules.recipientWindow(), RiskAlert::subject, alerts);

        return builder.build();
    }

    private static void emit(KStream<String, RiskAlert> stream, String store, Duration period,
            Function<RiskAlert, String> id, JsonSerde<RiskAlert> serde) {
        stream.process(() -> new FirstSeen<RiskAlert>(store, period, id), Named.as(store), store)
                .peek((k, a) -> Metrics.counter("wiselite.risk.alerts", "rule", a.rule()).increment())
                .to(Topics.RISK_ALERTS, Produced.with(Serdes.String(), serde));
    }

    private static KeyValue<String, RiskAlert> alert(String rule, Windowed<String> w, String details, Agg agg) {
        Window window = w.window();
        var id = UUID.nameUUIDFromBytes((rule + "|" + w.key() + "|" + window.start()).getBytes(StandardCharsets.UTF_8));
        return KeyValue.pair(w.key(), new RiskAlert(id, rule, w.key(), details, agg.transferIds(),
                Instant.ofEpochMilli(window.start()), Instant.ofEpochMilli(window.end())));
    }

    private static Materialized<String, Agg, WindowStore<Bytes, byte[]>> store(String name) {
        return Materialized.<String, Agg, WindowStore<Bytes, byte[]>>as(name)
                .withKeySerde(Serdes.String())
                .withValueSerde(new JsonSerde<>(Agg.class));
    }

    private static org.apache.kafka.streams.state.StoreBuilder<WindowStore<String, Long>> seenStore(String name,
            Duration period) {
        var retention = period.multipliedBy(2);
        return Stores.windowStoreBuilder(Stores.persistentWindowStore(name, retention, period, false),
                Serdes.String(), Serdes.Long());
    }
}
