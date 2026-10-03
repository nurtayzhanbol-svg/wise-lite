package com.wiselite.transfer.outbox;

import static com.wiselite.transfer.ledger.Posting.credit;
import static com.wiselite.transfer.ledger.Posting.debit;
import static org.assertj.core.api.Assertions.assertThat;

import com.wiselite.transfer.IntegrationTest;
import com.wiselite.transfer.ledger.AccountService;
import com.wiselite.transfer.ledger.AccountType;
import com.wiselite.transfer.ledger.JournalEntry;
import com.wiselite.transfer.ledger.JournalEntryType;
import com.wiselite.transfer.ledger.LedgerService;
import com.wiselite.transfer.ledger.Money;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots with the scheduled relay ON, using the real application.yml interval. Guards against
 * config that only breaks in production (e.g. an interval format @Scheduled can't parse).
 */
@IntegrationTest
class OutboxRelaySchedulerIT {

    @DynamicPropertySource
    static void enableRelay(DynamicPropertyRegistry registry) {
        registry.add("wiselite.outbox.relay.enabled", () -> "true");
    }

    @Autowired OutboxRelay relay;
    @Autowired AccountService accounts;
    @Autowired LedgerService ledger;
    @Autowired com.wiselite.transfer.transfer.TransferService transfers;

    @Test
    void scheduledRelayDrainsTheOutboxWithoutManualCalls() throws Exception {
        var eur = Currency.getInstance("EUR");
        var account = accounts.openCustomerAccount(UUID.randomUUID(), eur);
        var funding = accounts.systemAccount(AccountType.EXTERNAL_FUNDING, eur);
        ledger.post(JournalEntry.of(JournalEntryType.TOP_UP, "topup-" + account.id(),
                debit(funding.id(), Money.of("10.00", "EUR")), credit(account.id(), Money.of("10.00", "EUR"))));
        transfers.create(account.ownerId(), new com.wiselite.transfer.transfer.TransferService.CreateTransfer(account.id(),
                Money.of("1.00", "EUR"), new com.wiselite.transfer.transfer.Transfer.Recipient("Bob", "DE89370400440532013000")));

        long deadline = System.currentTimeMillis() + 15_000;
        while (relay.unpublishedCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(relay.unpublishedCount()).isZero();
    }
}
