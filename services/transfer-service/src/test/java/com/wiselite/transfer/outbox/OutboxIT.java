package com.wiselite.transfer.outbox;

import com.wiselite.outbox.OutboxPublishException;
import com.wiselite.outbox.OutboxRelay;
import com.wiselite.outbox.OutboxWriter;
import static com.wiselite.transfer.ledger.Posting.credit;
import static com.wiselite.transfer.ledger.Posting.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiselite.events.Topics;
import com.wiselite.events.TransferStateChanged;
import com.wiselite.transfer.IntegrationTest;
import com.wiselite.transfer.ledger.Account;
import com.wiselite.transfer.ledger.AccountService;
import com.wiselite.transfer.ledger.AccountType;
import com.wiselite.transfer.ledger.InsufficientFundsException;
import com.wiselite.transfer.ledger.JournalEntry;
import com.wiselite.transfer.ledger.JournalEntryType;
import com.wiselite.transfer.ledger.LedgerService;
import com.wiselite.transfer.ledger.Money;
import com.wiselite.transfer.transfer.Transfer;
import com.wiselite.transfer.transfer.TransferService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.testcontainers.kafka.KafkaContainer;

@IntegrationTest
class OutboxIT {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Transfer.Recipient BOB = new Transfer.Recipient("Bob", "DE89370400440532013000");

    @Autowired TransferService transfers;
    @Autowired AccountService accounts;
    @Autowired LedgerService ledger;
    @Autowired OutboxWriter writer;
    @Autowired OutboxRelay relay;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired KafkaContainer kafka;

    @Test
    void stateChangeAndEventCommitTogether() {
        var alice = funded("10.00");
        long before = outboxRows();

        var t = create(alice, "5.00");
        assertThat(outboxRows()).isEqualTo(before + 1);
        assertThat(eventTypesFor(t.id())).containsExactly("FUNDED");

        // Rolled-back state change: no event either.
        assertThatThrownBy(() -> create(alice, "50.00")).isInstanceOf(InsufficientFundsException.class);
        assertThat(outboxRows()).isEqualTo(before + 1);
    }

    @Test
    void writerRefusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> writer.append(Topics.TRANSFER_EVENTS, "k", "T", UUID.randomUUID(), Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void relayPublishesEveryStateChangeInOrderWithEventIds() throws Exception {
        var alice = funded("100.00");
        var t = create(alice, "25.00");
        transfers.approve(t.id(), "risk ALLOW");
        transfers.markProcessing(t.id());
        transfers.complete(t.id());

        drain();

        var records = consumeFor(t.id(), 4);
        assertThat(records).extracting(r -> parse(r).toState()).containsExactly("FUNDED", "APPROVED", "PROCESSING", "COMPLETED");
        for (var r : records) {
            assertThat(header(r, Topics.HEADER_EVENT_ID)).isEqualTo(parse(r).eventId().toString());
            assertThat(header(r, Topics.HEADER_EVENT_TYPE)).isEqualTo(TransferStateChanged.TYPE);
        }
        assertThat(relay.unpublishedCount()).isZero();
    }

    @Test
    void kafkaOutageKeepsEventsInTheOutboxUntilItRecovers() throws Exception {
        drain();
        var alice = funded("100.00");
        var docker = kafka.getDockerClient();

        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        Transfer t;
        try {
            // The business operation does not depend on Kafka at all.
            t = create(alice, "10.00");
            assertThatThrownBy(relay::publishBatch).isInstanceOf(OutboxPublishException.class);
            assertThat(relay.unpublishedCount()).isPositive();
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        awaitDrained();
        assertThat(consumeFor(t.id(), 1)).extracting(r -> parse(r).toState()).contains("FUNDED");
    }

    private void drain() {
        while (relay.publishBatch() > 0) {
            // keep going
        }
    }

    private void awaitDrained() throws InterruptedException {
        var deadline = Instant.now().plusSeconds(30);
        while (true) {
            try {
                drain();
                return;
            } catch (OutboxPublishException e) {
                if (Instant.now().isAfter(deadline)) {
                    throw e;
                }
                Thread.sleep(500);
            }
        }
    }

    private List<ConsumerRecord<String, String>> consumeFor(UUID transferId, int expected) {
        var props = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var found = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.TRANSFER_EVENTS));
            var deadline = Instant.now().plusSeconds(20);
            while (found.size() < expected && Instant.now().isBefore(deadline)) {
                for (var r : consumer.poll(Duration.ofMillis(500))) {
                    if (r.key().equals(transferId.toString())) {
                        found.add(r);
                    }
                }
            }
        }
        return found;
    }

    private TransferStateChanged parse(ConsumerRecord<String, String> r) {
        try {
            return json.readValue(r.value(), TransferStateChanged.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private long outboxRows() {
        return jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single();
    }

    private List<String> eventTypesFor(UUID transferId) {
        return jdbc.sql("SELECT payload FROM outbox_events WHERE event_key = ? ORDER BY seq").param(transferId.toString())
                .query(String.class).list().stream()
                .map(p -> {
                    try {
                        return json.readValue(p, TransferStateChanged.class).toState();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
    }

    private Transfer create(Account from, String amount) {
        return transfers.create(from.ownerId(), new TransferService.CreateTransfer(from.id(), Money.of(amount, "EUR"), BOB));
    }

    private Account funded(String amount) {
        var account = accounts.openCustomerAccount(UUID.randomUUID(), EUR);
        var funding = accounts.systemAccount(AccountType.EXTERNAL_FUNDING, EUR);
        ledger.post(JournalEntry.of(JournalEntryType.TOP_UP, "topup-" + account.id(),
                debit(funding.id(), Money.of(amount, "EUR")), credit(account.id(), Money.of(amount, "EUR"))));
        return account;
    }
}
