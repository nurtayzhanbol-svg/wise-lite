package com.wiselite.transfer.ledger;

import static com.wiselite.transfer.ledger.Posting.credit;
import static com.wiselite.transfer.ledger.Posting.debit;
import static org.assertj.core.api.Assertions.assertThat;

import com.wiselite.transfer.IntegrationTest;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The production ledger under real concurrent load (Postgres, 16 threads). */
@IntegrationTest
class LedgerConcurrencyIT {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired LedgerService ledger;
    @Autowired AccountService accounts;
    @Autowired LedgerRepository repository;

    enum Outcome { OK, INSUFFICIENT_FUNDS }

    @Test
    void concurrentDebitsNeverOverdraw() throws Exception {
        var alice = funded("100.00");
        var sinks = new ArrayList<Account>();
        for (int i = 0; i < 20; i++) {
            sinks.add(accounts.openCustomerAccount(UUID.randomUUID(), EUR));
        }

        var outcomes = runConcurrently(sinks.stream().<Callable<Outcome>>map(sink -> () -> move(alice, sink, "10.00")).toList());

        assertThat(outcomes).filteredOn(o -> o == Outcome.OK).hasSize(10);
        assertThat(outcomes).filteredOn(o -> o == Outcome.INSUFFICIENT_FUNDS).hasSize(10);
        assertThat(accounts.balance(alice.id())).isEqualTo(Money.of("0.00", "EUR"));
        assertThat(repository.sumEntries(alice.id())).isZero();
    }

    @Test
    void opposingTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        var alice = funded("50.00");
        var bob = funded("50.00");
        var tasks = new ArrayList<Callable<Outcome>>();
        for (int i = 0; i < 200; i++) {
            tasks.add(i % 2 == 0 ? () -> move(alice, bob, "1.00") : () -> move(bob, alice, "1.00"));
        }

        runConcurrently(tasks);

        assertThat(accounts.balance(alice.id()).plus(accounts.balance(bob.id()))).isEqualTo(Money.of("100.00", "EUR"));
        assertThat(repository.sumEntries(alice.id())).isEqualTo(accounts.balance(alice.id()).minor());
        assertThat(repository.sumEntries(bob.id())).isEqualTo(accounts.balance(bob.id()).minor());
    }

    private Outcome move(Account from, Account to, String amount) {
        try {
            ledger.post(JournalEntry.of(JournalEntryType.TRANSFER, "c-" + UUID.randomUUID(),
                    debit(from.id(), Money.of(amount, "EUR")), credit(to.id(), Money.of(amount, "EUR"))));
            return Outcome.OK;
        } catch (InsufficientFundsException e) {
            return Outcome.INSUFFICIENT_FUNDS;
        }
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        var start = new CountDownLatch(1);
        var results = new ArrayList<T>();
        try (var pool = Executors.newFixedThreadPool(16)) {
            var futures = tasks.stream().map(t -> pool.submit(() -> {
                start.await();
                return t.call();
            })).toList();
            start.countDown();
            for (var f : futures) {
                results.add(f.get()); // any unexpected exception (e.g. deadlock) fails the test here
            }
        }
        return results;
    }

    private Account funded(String amount) {
        var account = accounts.openCustomerAccount(UUID.randomUUID(), EUR);
        var funding = accounts.systemAccount(AccountType.EXTERNAL_FUNDING, EUR);
        ledger.post(JournalEntry.of(JournalEntryType.TOP_UP, "topup-" + account.id(),
                debit(funding.id(), Money.of(amount, "EUR")), credit(account.id(), Money.of(amount, "EUR"))));
        return account;
    }
}
