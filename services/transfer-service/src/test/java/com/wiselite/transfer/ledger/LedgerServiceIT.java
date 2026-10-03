package com.wiselite.transfer.ledger;

import static com.wiselite.transfer.ledger.Posting.credit;
import static com.wiselite.transfer.ledger.Posting.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wiselite.transfer.IntegrationTest;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class LedgerServiceIT {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired
    LedgerService ledger;

    @Autowired
    AccountService accounts;

    @Autowired
    LedgerRepository repository;

    @Test
    void topUpThenTransferMovesMoneyAndKeepsTheBooksBalanced() {
        var alice = accounts.openCustomerAccount(UUID.randomUUID(), EUR);
        var bob = accounts.openCustomerAccount(UUID.randomUUID(), EUR);
        var funding = accounts.systemAccount(AccountType.EXTERNAL_FUNDING, EUR);
        var fundingBefore = accounts.balance(funding.id());

        ledger.post(JournalEntry.of(JournalEntryType.TOP_UP, "topup-" + alice.id(),
                debit(funding.id(), Money.of("100.00", "EUR")),
                credit(alice.id(), Money.of("100.00", "EUR"))));
        ledger.post(JournalEntry.of(JournalEntryType.TRANSFER, "t-" + UUID.randomUUID(),
                debit(alice.id(), Money.of("30.00", "EUR")),
                credit(bob.id(), Money.of("30.00", "EUR"))));

        assertThat(accounts.balance(alice.id())).isEqualTo(Money.of("70.00", "EUR"));
        assertThat(accounts.balance(bob.id())).isEqualTo(Money.of("30.00", "EUR"));
        assertThat(accounts.balance(funding.id())).isEqualTo(fundingBefore.minus(Money.of("100.00", "EUR")));
    }

    @Test
    void insufficientFundsRejectsTheWholeEntryAndPersistsNothing() {
        var alice = funded("50.00");
        var bob = accounts.openCustomerAccount(UUID.randomUUID(), EUR);
        var reference = "t-" + UUID.randomUUID();

        assertThatThrownBy(() -> ledger.post(JournalEntry.of(JournalEntryType.TRANSFER, reference,
                debit(alice.id(), Money.of("80.00", "EUR")),
                credit(bob.id(), Money.of("80.00", "EUR")))))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(accounts.balance(alice.id())).isEqualTo(Money.of("50.00", "EUR"));
        assertThat(accounts.balance(bob.id())).isEqualTo(Money.zero(EUR));
        assertThat(repository.countJournalEntries(reference)).isZero();
    }

    @Test
    void postingInTheWrongCurrencyIsRejected() {
        var alice = funded("50.00");
        var usdAccount = accounts.openCustomerAccount(UUID.randomUUID(), Currency.getInstance("USD"));

        assertThatThrownBy(() -> ledger.post(JournalEntry.of(JournalEntryType.TRANSFER, "t-" + UUID.randomUUID(),
                debit(alice.id(), Money.of("10.00", "EUR")),
                credit(usdAccount.id(), Money.of("10.00", "EUR")))))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void unknownAccountIsRejected() {
        var alice = funded("50.00");
        assertThatThrownBy(() -> ledger.post(JournalEntry.of(JournalEntryType.TRANSFER, "t-" + UUID.randomUUID(),
                debit(alice.id(), Money.of("10.00", "EUR")),
                credit(UUID.randomUUID(), Money.of("10.00", "EUR")))))
                .isInstanceOf(AccountNotFoundException.class);
    }

    /**
     * Randomised sequence of transfers between a few accounts (fixed seed, so failures are
     * reproducible). Afterwards the balance projection must equal the sum of ledger entries,
     * no customer balance may be negative, and the total amount of money must be unchanged.
     */
    @Test
    void balanceProjectionAlwaysMatchesTheLedger() {
        var random = new Random(42);
        List<Account> customers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            customers.add(funded("1000.00"));
        }
        int rejected = 0;
        for (int i = 0; i < 300; i++) {
            var from = customers.get(random.nextInt(customers.size()));
            var to = customers.get(random.nextInt(customers.size()));
            if (from.equals(to)) {
                continue;
            }
            var amount = new Money(1 + random.nextInt(60_000), EUR);
            try {
                ledger.post(JournalEntry.of(JournalEntryType.TRANSFER, "rand-" + i, debit(from.id(), amount), credit(to.id(), amount)));
            } catch (InsufficientFundsException e) {
                rejected++;
            }
        }

        long total = 0;
        for (var c : customers) {
            long projected = accounts.balance(c.id()).minor();
            assertThat(projected).isEqualTo(repository.sumEntries(c.id())).isNotNegative();
            total += projected;
        }
        assertThat(total).isEqualTo(5 * 100_000L);
        assertThat(rejected).as("the scenario should exercise the insufficient-funds path").isPositive();
    }

    private Account funded(String amount) {
        var account = accounts.openCustomerAccount(UUID.randomUUID(), EUR);
        var funding = accounts.systemAccount(AccountType.EXTERNAL_FUNDING, EUR);
        ledger.post(JournalEntry.of(JournalEntryType.TOP_UP, "topup-" + account.id(),
                debit(funding.id(), Money.of(amount, "EUR")),
                credit(account.id(), Money.of(amount, "EUR"))));
        return account;
    }
}
