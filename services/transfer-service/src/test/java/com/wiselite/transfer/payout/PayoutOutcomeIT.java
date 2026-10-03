package com.wiselite.transfer.payout;

import static com.wiselite.transfer.ledger.Posting.credit;
import static com.wiselite.transfer.ledger.Posting.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiselite.events.PayoutStatusChanged;
import com.wiselite.events.Topics;
import com.wiselite.transfer.IntegrationTest;
import com.wiselite.transfer.ledger.Account;
import com.wiselite.transfer.ledger.AccountService;
import com.wiselite.transfer.ledger.AccountType;
import com.wiselite.transfer.ledger.JournalEntry;
import com.wiselite.transfer.ledger.JournalEntryType;
import com.wiselite.transfer.ledger.LedgerService;
import com.wiselite.transfer.ledger.Money;
import com.wiselite.transfer.transfer.Transfer;
import com.wiselite.transfer.transfer.TransferService;
import com.wiselite.transfer.transfer.TransferState;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/** Payout outcomes arrive over Kafka and drive the transfer to COMPLETED or REFUNDED. */
@IntegrationTest
class PayoutOutcomeIT {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired TransferService transfers;
    @Autowired AccountService accounts;
    @Autowired LedgerService ledger;
    @Autowired PayoutOutcomeHandler handler;

    @Test
    void settledPayoutCompletesTheTransfer() throws Exception {
        var t = fundedTransfer("100.00", "40.00");

        publish(new PayoutStatusChanged(UUID.randomUUID(), t.id(), PayoutStatusChanged.SETTLED, null, Instant.now()));

        await().atMost(Duration.ofSeconds(20)).until(() -> transfers.get(t.id()).state() == TransferState.COMPLETED);
    }

    @Test
    void rejectedPayoutRefundsTheCustomer() throws Exception {
        var t = fundedTransfer("100.00", "40.00");

        publish(new PayoutStatusChanged(UUID.randomUUID(), t.id(), PayoutStatusChanged.REJECTED, "invalid IBAN", Instant.now()));

        await().atMost(Duration.ofSeconds(20)).until(() -> transfers.get(t.id()).state() == TransferState.REFUNDED);
        assertThat(accounts.balance(t.sourceAccountId())).isEqualTo(Money.of("100.00", "EUR"));
    }

    @Test
    void duplicateOutcomeEventsAreApplied_once() {
        var t = fundedTransfer("100.00", "40.00");
        var event = new PayoutStatusChanged(UUID.randomUUID(), t.id(), PayoutStatusChanged.SETTLED, null, Instant.now());

        assertThat(handler.handle(event)).isEqualTo(PayoutOutcomeHandler.Outcome.APPLIED);
        assertThat(handler.handle(event)).isEqualTo(PayoutOutcomeHandler.Outcome.DUPLICATE);
        // A *different* event with the same outcome is also harmless (state machine no-op).
        handler.handle(new PayoutStatusChanged(UUID.randomUUID(), t.id(), PayoutStatusChanged.SETTLED, null, Instant.now()));

        assertThat(transfers.get(t.id()).state()).isEqualTo(TransferState.COMPLETED);
        assertThat(accounts.balance(t.sourceAccountId())).isEqualTo(Money.of("60.00", "EUR"));
    }

    private void publish(PayoutStatusChanged event) throws Exception {
        kafka.send(Topics.PAYOUT_EVENTS, event.transferId().toString(), json.writeValueAsString(event)).get();
    }

    private Transfer fundedTransfer(String topUp, String amount) {
        Account account = accounts.openCustomerAccount(UUID.randomUUID(), EUR);
        var funding = accounts.systemAccount(AccountType.EXTERNAL_FUNDING, EUR);
        ledger.post(JournalEntry.of(JournalEntryType.TOP_UP, "topup-" + account.id(),
                debit(funding.id(), Money.of(topUp, "EUR")), credit(account.id(), Money.of(topUp, "EUR"))));
        var t = transfers.create(account.ownerId(), new TransferService.CreateTransfer(account.id(), Money.of(amount, "EUR"),
                new Transfer.Recipient("Bob", "DE89370400440532013000")));
        return transfers.approve(t.id(), "risk ALLOW");
    }
}
