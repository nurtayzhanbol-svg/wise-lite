package com.wiselite.transfer.transfer;

import static com.wiselite.transfer.ledger.Posting.credit;
import static com.wiselite.transfer.ledger.Posting.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wiselite.transfer.IntegrationTest;
import com.wiselite.transfer.ledger.Account;
import com.wiselite.transfer.ledger.AccountNotFoundException;
import com.wiselite.transfer.ledger.AccountService;
import com.wiselite.transfer.ledger.AccountType;
import com.wiselite.transfer.ledger.CurrencyMismatchException;
import com.wiselite.transfer.ledger.InsufficientFundsException;
import com.wiselite.transfer.ledger.JournalEntry;
import com.wiselite.transfer.ledger.JournalEntryType;
import com.wiselite.transfer.ledger.LedgerRepository;
import com.wiselite.transfer.ledger.LedgerService;
import com.wiselite.transfer.ledger.Money;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class TransferServiceIT {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Transfer.Recipient BOB = new Transfer.Recipient("Bob", "DE89370400440532013000");

    @Autowired TransferService transfers;
    @Autowired AccountService accounts;
    @Autowired LedgerService ledger;
    @Autowired LedgerRepository ledgerRepository;

    @Test
    void createReservesMoneyInClearing() {
        var alice = funded("100.00");
        var clearingBefore = clearingBalance();

        var t = transfers.create(alice.ownerId(), new TransferService.CreateTransfer(alice.id(), Money.of("40.00", "EUR"), BOB));

        assertThat(t.state()).isEqualTo(TransferState.FUNDED);
        assertThat(accounts.balance(alice.id())).isEqualTo(Money.of("60.00", "EUR"));
        assertThat(clearingBalance()).isEqualTo(clearingBefore.plus(Money.of("40.00", "EUR")));
        assertThat(transfers.history(t.id())).extracting(Transfer.StateChange::to)
                .containsExactly(TransferState.CREATED, TransferState.FUNDED);
    }

    @Test
    void happyPathPaysOutFromClearing() {
        var alice = funded("100.00");
        var t = create(alice, "25.00");
        var clearingBefore = clearingBalance();

        transfers.markProcessing(t.id());
        var done = transfers.complete(t.id());

        assertThat(done.state()).isEqualTo(TransferState.COMPLETED);
        assertThat(clearingBalance()).isEqualTo(clearingBefore.minus(Money.of("25.00", "EUR")));
        assertThat(accounts.balance(alice.id())).isEqualTo(Money.of("75.00", "EUR"));
    }

    @Test
    void failureRefundsTheCustomer() {
        var alice = funded("100.00");
        var t = create(alice, "25.00");
        transfers.markProcessing(t.id());

        var refunded = transfers.fail(t.id(), "bank rejected: account closed");

        assertThat(refunded.state()).isEqualTo(TransferState.REFUNDED);
        assertThat(accounts.balance(alice.id())).isEqualTo(Money.of("100.00", "EUR"));
        assertThat(transfers.history(t.id())).extracting(Transfer.StateChange::to).containsExactly(
                TransferState.CREATED, TransferState.FUNDED, TransferState.PROCESSING, TransferState.FAILED, TransferState.REFUNDED);
        assertThat(transfers.history(t.id()).get(3).reason()).isEqualTo("bank rejected: account closed");
    }

    @Test
    void duplicateEventsAreNoOps() {
        var alice = funded("100.00");
        var t = create(alice, "25.00");
        transfers.markProcessing(t.id());
        transfers.complete(t.id());

        transfers.complete(t.id());
        transfers.complete(t.id());

        assertThat(ledgerRepository.countJournalEntries("transfer:" + t.id() + ":payout")).isEqualTo(1);
        assertThat(transfers.history(t.id())).hasSize(4);
    }

    @Test
    void duplicateFailureRefundsOnce() {
        var alice = funded("100.00");
        var t = create(alice, "25.00");

        transfers.fail(t.id(), "first");
        transfers.fail(t.id(), "second");

        assertThat(accounts.balance(alice.id())).isEqualTo(Money.of("100.00", "EUR"));
        assertThat(ledgerRepository.countJournalEntries("transfer:" + t.id() + ":refund")).isEqualTo(1);
    }

    @Test
    void illegalTransitionsAreRejectedAndChangeNothing() {
        var alice = funded("100.00");
        var t = create(alice, "25.00");

        assertThatThrownBy(() -> transfers.complete(t.id())).isInstanceOf(IllegalStateTransitionException.class);

        transfers.markProcessing(t.id());
        transfers.complete(t.id());
        assertThatThrownBy(() -> transfers.fail(t.id(), "too late")).isInstanceOf(IllegalStateTransitionException.class);
        assertThat(transfers.get(t.id()).state()).isEqualTo(TransferState.COMPLETED);
        assertThat(accounts.balance(alice.id())).isEqualTo(Money.of("75.00", "EUR"));
    }

    @Test
    void insufficientFundsLeavesNoTransferBehind() {
        var alice = funded("10.00");

        assertThatThrownBy(() -> create(alice, "10.01")).isInstanceOf(InsufficientFundsException.class);
        assertThat(accounts.balance(alice.id())).isEqualTo(Money.of("10.00", "EUR"));
    }

    @Test
    void cannotSpendSomeoneElsesAccount() {
        var alice = funded("100.00");

        assertThatThrownBy(() -> transfers.create(UUID.randomUUID(),
                new TransferService.CreateTransfer(alice.id(), Money.of("1.00", "EUR"), BOB)))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void currencyMustMatchSourceAccount() {
        var alice = funded("100.00");

        assertThatThrownBy(() -> transfers.create(alice.ownerId(),
                new TransferService.CreateTransfer(alice.id(), Money.of("1.00", "GBP"), BOB)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    private Transfer create(Account from, String amount) {
        return transfers.create(from.ownerId(), new TransferService.CreateTransfer(from.id(), Money.of(amount, "EUR"), BOB));
    }

    private Money clearingBalance() {
        return accounts.balance(accounts.systemAccount(AccountType.PAYOUT_CLEARING, EUR).id());
    }

    private Account funded(String amount) {
        var account = accounts.openCustomerAccount(UUID.randomUUID(), EUR);
        var funding = accounts.systemAccount(AccountType.EXTERNAL_FUNDING, EUR);
        ledger.post(JournalEntry.of(JournalEntryType.TOP_UP, "topup-" + account.id(),
                debit(funding.id(), Money.of(amount, "EUR")), credit(account.id(), Money.of(amount, "EUR"))));
        return account;
    }
}
