package com.wiselite.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.wiselite.events.RiskAlert;
import com.wiselite.events.Topics;
import com.wiselite.events.TransferStateChanged;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The topology without a broker: deterministic event time, millisecond runs. */
class RiskTopologyTest {

    static final RiskRules RULES = new RiskRules(Duration.ofMinutes(10), 5, Duration.ofDays(1), 1_000_000,
            Duration.ofHours(1), 3, Duration.ofMinutes(5), Duration.ofHours(24), 5_000_000, 50_000_000, List.of());
    static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");
    static final String IBAN = "DE89370400440532013000";

    @TempDir Path stateDir;
    TopologyTestDriver driver;
    TestInputTopic<String, String> in;
    TestOutputTopic<String, RiskAlert> out;

    @BeforeEach
    void start() {
        driver = newDriver(stateDir);
        in = driver.createInputTopic(Topics.TRANSFER_EVENTS, new StringSerializer(), new StringSerializer());
        out = driver.createOutputTopic(Topics.RISK_ALERTS, new StringDeserializer(),
                new JsonSerde<>(RiskAlert.class).deserializer());
    }

    @AfterEach
    void stop() {
        driver.close();
    }

    @Test
    void burstAboveTheVelocityLimitAlertsOnce() {
        var owner = UUID.randomUUID();
        for (int i = 0; i < 8; i++) {
            send(funded(owner, "IBAN-" + i, 1_00, T0.plus(Duration.ofMinutes(i))));
        }
        var alerts = out.readValuesToList();
        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.rule()).isEqualTo(RiskAlert.VELOCITY);
            assertThat(a.subject()).isEqualTo(owner.toString());
            assertThat(a.transferIds()).hasSize(6);
        });
    }

    @Test
    void transfersSpreadOutDoNotAlert() {
        var owner = UUID.randomUUID();
        for (int i = 0; i < 12; i++) {
            send(funded(owner, IBAN, 1_00, T0.plus(Duration.ofMinutes(3L * i)))); // at most 4 per 10 minutes
        }
        assertThat(out.isEmpty()).isTrue();
    }

    @Test
    void redeliveredEventsAreCountedOnce() {
        var owner = UUID.randomUUID();
        var events = new ArrayList<TransferStateChanged>();
        for (int i = 0; i < 5; i++) {
            events.add(funded(owner, IBAN, 1_00, T0.plus(Duration.ofMinutes(i))));
        }
        events.forEach(this::send);
        events.forEach(this::send); // the outbox relay re-published everything
        events.forEach(this::send);
        assertThat(out.isEmpty()).as("5 distinct transfers are at the limit, not over it").isTrue();
    }

    @Test
    void onlyFundedTransitionsCount() {
        var owner = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            var e = funded(owner, IBAN, 1_00, T0.plusSeconds(i));
            send(new TransferStateChanged(e.eventId(), e.transferId(), owner, "PROCESSING", "COMPLETED", 1_00, "EUR",
                    "Bob", IBAN, null, e.occurredAt()));
        }
        assertThat(out.isEmpty()).isTrue();
    }

    @Test
    void dailyVolumeAlertsOncePerDayAndCurrency() {
        var owner = UUID.randomUUID();
        for (int day = 0; day < 2; day++) {
            for (int i = 0; i < 4; i++) {
                send(funded(owner, IBAN, 400_000, T0.plus(Duration.ofDays(day)).plus(Duration.ofHours(i))));
            }
        }
        var alerts = out.readValuesToList();
        assertThat(alerts).extracting(RiskAlert::rule).containsExactly(RiskAlert.DAILY_VOLUME, RiskAlert.DAILY_VOLUME);
        assertThat(alerts).extracting(RiskAlert::subject).containsOnly(owner + "|EUR");
        assertThat(alerts.get(0).windowStart()).isNotEqualTo(alerts.get(1).windowStart());
    }

    @Test
    void manySendersToOneRecipientLooksLikeAMule() {
        for (int i = 0; i < 4; i++) {
            send(funded(UUID.randomUUID(), "DE89 3704 0044 0532 0130 00", 50_00, T0.plus(Duration.ofMinutes(5L * i))));
        }
        assertThat(out.readValuesToList()).singleElement().satisfies(a -> {
            assertThat(a.rule()).isEqualTo(RiskAlert.MULE_RECIPIENT);
            assertThat(a.subject()).isEqualTo(IBAN);
        });

        var sameOwner = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            send(funded(sameOwner, "GB82WEST12345698765432", 50_00, T0.plus(Duration.ofMinutes(5L * i))));
        }
        assertThat(out.isEmpty()).as("one sender repeatedly is not fan-in").isTrue();
    }

    @Test
    void lateEventWithinGraceIsCounted() {
        var owner = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            send(funded(owner, IBAN, 1_00, T0.plus(Duration.ofMinutes(i))));
        }
        send(funded(UUID.randomUUID(), "X", 1_00, T0.plus(Duration.ofMinutes(8)))); // stream time moves on
        send(funded(owner, IBAN, 1_00, T0.plus(Duration.ofMinutes(5)))); // 3 min late, grace is 5
        assertThat(out.readValuesToList()).extracting(RiskAlert::rule).containsExactly(RiskAlert.VELOCITY);
    }

    @Test
    void eventLaterThanGraceIsDropped() {
        var owner = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            send(funded(owner, IBAN, 1_00, T0.plus(Duration.ofMinutes(i))));
        }
        send(funded(UUID.randomUUID(), "X", 1_00, T0.plus(Duration.ofMinutes(30))));
        send(funded(owner, IBAN, 1_00, T0.plus(Duration.ofMinutes(5)))); // 25 min late
        assertThat(out.isEmpty()).isTrue();
    }

    @Test
    void malformedRecordIsSkippedAndProcessingContinues() {
        in.pipeInput("k", "{not json");
        var owner = UUID.randomUUID();
        for (int i = 0; i < 6; i++) {
            send(funded(owner, IBAN, 1_00, T0.plus(Duration.ofMinutes(i))));
        }
        assertThat(out.readValuesToList()).hasSize(1);
    }

    @Test
    void reprocessingTheSameInputYieldsTheSameAlertIds(@TempDir Path otherStateDir) {
        var owner = UUID.randomUUID();
        var events = new ArrayList<TransferStateChanged>();
        for (int i = 0; i < 7; i++) {
            events.add(funded(owner, IBAN, 300_000, T0.plus(Duration.ofMinutes(i))));
        }
        events.forEach(this::send);
        var first = out.readValuesToList().stream().map(RiskAlert::alertId).toList();

        try (var replay = newDriver(otherStateDir)) {
            var replayIn = replay.createInputTopic(Topics.TRANSFER_EVENTS, new StringSerializer(), new StringSerializer());
            var replayOut = replay.createOutputTopic(Topics.RISK_ALERTS, new StringDeserializer(),
                    new JsonSerde<>(RiskAlert.class).deserializer());
            events.forEach(e -> replayIn.pipeInput(e.transferId().toString(), json(e)));
            assertThat(replayOut.readValuesToList().stream().map(RiskAlert::alertId).toList())
                    .isEqualTo(first).hasSize(2); // velocity + daily volume
        }
    }

    private static TopologyTestDriver newDriver(Path dir) {
        var props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "risk-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        props.put(StreamsConfig.STATE_DIR_CONFIG, dir.toString());
        props.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);
        return new TopologyTestDriver(RiskTopology.build(RULES), props);
    }

    private void send(TransferStateChanged e) {
        in.pipeInput(e.transferId().toString(), json(e));
    }

    static String json(TransferStateChanged e) {
        try {
            return JsonSerde.MAPPER.writeValueAsString(e);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static TransferStateChanged funded(UUID owner, String iban, long amountMinor, Instant at) {
        return new TransferStateChanged(UUID.randomUUID(), UUID.randomUUID(), owner, "CREATED", "FUNDED", amountMinor,
                "EUR", "Bob", iban, null, at);
    }

    static List<TransferStateChanged> burst(UUID owner, int n) {
        var list = new ArrayList<TransferStateChanged>();
        for (int i = 0; i < n; i++) {
            list.add(funded(owner, IBAN, 1_00, Instant.now().minusSeconds(60).plusSeconds(i)));
        }
        return list;
    }
}
