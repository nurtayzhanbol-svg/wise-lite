package com.wiselite.risk;

import static com.wiselite.risk.RiskTopologyTest.IBAN;
import static com.wiselite.risk.RiskTopologyTest.RULES;
import static com.wiselite.risk.RiskTopologyTest.T0;
import static com.wiselite.risk.RiskTopologyTest.funded;
import static com.wiselite.risk.RiskTopologyTest.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.wiselite.events.RiskDecision;
import com.wiselite.events.Topics;
import com.wiselite.events.TransferStateChanged;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One decision per FUNDED transfer on risk.decisions.v1: the gate's input. */
class RiskDecisionTopologyTest {

    static final RiskRules GATE_RULES = new RiskRules(RULES.velocityWindow(), 5, RULES.volumeWindow(), 1_000_000,
            RULES.recipientWindow(), RULES.maxSendersPerRecipient(), RULES.grace(), RULES.dedupeRetention(),
            300_000, 600_000, List.of("DE00 BLOC KED0 0000 0000 00"));

    @TempDir Path stateDir;
    TopologyTestDriver driver;
    TestInputTopic<String, String> in;
    TestOutputTopic<String, RiskDecision> out;

    @BeforeEach
    void start() {
        driver = newDriver(stateDir);
        in = driver.createInputTopic(Topics.TRANSFER_EVENTS, new StringSerializer(), new StringSerializer());
        out = driver.createOutputTopic(Topics.RISK_DECISIONS, new StringDeserializer(),
                new JsonSerde<>(RiskDecision.class).deserializer());
    }

    @AfterEach
    void stop() {
        driver.close();
    }

    @Test
    void amountThresholdsGiveAllowReviewBlock() {
        var owner = UUID.randomUUID();
        var small = funded(owner, IBAN, 10_00, T0);
        var big = funded(owner, IBAN, 3_000_00, T0.plusSeconds(1));
        var huge = funded(owner, IBAN, 6_000_00, T0.plusSeconds(2));
        send(small);
        send(big);
        send(huge);

        var decisions = out.readRecordsToList();
        assertThat(decisions).extracting(TestRecord::key)
                .as("keyed by transfer: decisions follow the transfer's partition")
                .containsExactly(small.transferId().toString(), big.transferId().toString(), huge.transferId().toString());
        assertThat(decisions).extracting(r -> r.value().decision())
                .containsExactly(RiskDecision.ALLOW, RiskDecision.REVIEW, RiskDecision.BLOCK);
        assertThat(decisions.get(1).value().reasons()).containsExactly("AMOUNT_ABOVE_REVIEW_LIMIT");
        assertThat(decisions).allSatisfy(r -> assertThat(r.value().decisionId()).isEqualTo(RiskDecision.idFor(r.value().transferId())));
    }

    @Test
    void blocklistedRecipientIsBlockedWhateverTheSpacing() {
        send(funded(UUID.randomUUID(), "de00 blocked00000000000", 1_00, T0));
        assertThat(out.readValue().reasons()).containsExactly("RECIPIENT_BLOCKLISTED");
    }

    @Test
    void velocityAboveTheLimitGoesToReview() {
        var owner = UUID.randomUUID();
        for (int i = 0; i < 7; i++) {
            send(funded(owner, "IBAN-" + i, 1_00, T0.plus(Duration.ofMinutes(i))));
        }
        assertThat(out.readValuesToList()).extracting(RiskDecision::decision).containsExactly(
                RiskDecision.ALLOW, RiskDecision.ALLOW, RiskDecision.ALLOW, RiskDecision.ALLOW, RiskDecision.ALLOW,
                RiskDecision.REVIEW, RiskDecision.REVIEW);
    }

    @Test
    void rollingVolumeAboveTheLimitGoesToReview_blockedMoneyDoesNotCount() {
        var owner = UUID.randomUUID();
        send(funded(owner, IBAN, 6_000_00, T0)); // BLOCK, not counted
        send(funded(owner, IBAN, 2_900_00, T0.plusSeconds(1)));
        send(funded(owner, IBAN, 2_900_00, T0.plusSeconds(2)));
        send(funded(owner, IBAN, 2_900_00, T0.plusSeconds(3)));
        send(funded(owner, IBAN, 2_900_00, T0.plusSeconds(4))); // 11,600.00 > 10,000.00
        assertThat(out.readValuesToList()).extracting(RiskDecision::decision).containsExactly(
                RiskDecision.BLOCK, RiskDecision.ALLOW, RiskDecision.ALLOW, RiskDecision.ALLOW, RiskDecision.REVIEW);
    }

    @Test
    void redeliveredEventGetsNoSecondDecision() {
        var e = funded(UUID.randomUUID(), IBAN, 1_00, T0);
        send(e);
        send(e);
        assertThat(out.readValuesToList()).hasSize(1);
    }

    /** Same transfer, new event id (outbox re-publish with a new id is impossible, but a buggy producer isn't). */
    @Test
    void sameTransferUnderANewEventIdGetsTheSameDecision() {
        var owner = UUID.randomUUID();
        var e = funded(owner, IBAN, 1_00, T0.plusSeconds(10)); // the 7th in the window → REVIEW
        for (int i = 0; i < 6; i++) {
            send(funded(owner, IBAN, 1_00, T0.plusSeconds(i + 1)));
        }
        send(e);
        assertThat(out.readValuesToList()).last().extracting(RiskDecision::decision).isEqualTo(RiskDecision.REVIEW);
        var again = new TransferStateChanged(UUID.randomUUID(), e.transferId(), owner, "CREATED", "FUNDED", 1_00, "EUR",
                "Bob", IBAN, null, e.occurredAt());
        send(again);
        var decisions = out.readValuesToList();
        assertThat(decisions).singleElement().satisfies(d -> {
            assertThat(d.decisionId()).isEqualTo(RiskDecision.idFor(e.transferId()));
            assertThat(d.decision()).isEqualTo(RiskDecision.REVIEW);
        });
    }

    @Test
    void nonFundedTransitionsGetNoDecision() {
        var e = funded(UUID.randomUUID(), IBAN, 1_00, T0);
        in.pipeInput(e.transferId().toString(), json(new TransferStateChanged(UUID.randomUUID(), e.transferId(), e.ownerId(),
                "FUNDED", "APPROVED", 1_00, "EUR", "Bob", IBAN, null, T0)));
        assertThat(out.isEmpty()).isTrue();
    }

    private TopologyTestDriver newDriver(Path dir) {
        var props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "risk-decision-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        props.put(StreamsConfig.STATE_DIR_CONFIG, dir.toString());
        props.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);
        return new TopologyTestDriver(RiskTopology.build(GATE_RULES), props);
    }

    private void send(TransferStateChanged e) {
        in.pipeInput(e.transferId().toString(), json(e), e.occurredAt());
    }
}
