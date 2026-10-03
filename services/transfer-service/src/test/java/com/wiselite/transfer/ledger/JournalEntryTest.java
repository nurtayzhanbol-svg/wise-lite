package com.wiselite.transfer.ledger;

import static com.wiselite.transfer.ledger.Posting.credit;
import static com.wiselite.transfer.ledger.Posting.debit;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class JournalEntryTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void fxConversionBalancesPerCurrency() {
        var customerEur = UUID.randomUUID();
        var customerUsd = UUID.randomUUID();
        var poolEur = UUID.randomUUID();
        var poolUsd = UUID.randomUUID();
        assertThatCode(() -> JournalEntry.of(JournalEntryType.FX_CONVERSION, "fx-1",
                debit(customerEur, Money.of("100.00", "EUR")),
                credit(poolEur, Money.of("100.00", "EUR")),
                debit(poolUsd, Money.of("108.00", "USD")),
                credit(customerUsd, Money.of("108.00", "USD"))))
                .doesNotThrowAnyException();
    }

    @Test
    void sumsAreCheckedPerCurrencyNotInTotal() {
        // EUR +100 and USD -100 "sum to zero" numerically but create EUR and destroy USD.
        assertThatThrownBy(() -> JournalEntry.of(JournalEntryType.ADJUSTMENT, "bad",
                credit(UUID.randomUUID(), Money.of("100", "EUR")),
                debit(UUID.randomUUID(), Money.of("100", "USD"))))
                .isInstanceOf(UnbalancedJournalEntryException.class);
    }

    @Test
    void needsAtLeastTwoPostings() {
        assertThatThrownBy(() -> JournalEntry.of(JournalEntryType.ADJUSTMENT, "single",
                credit(UUID.randomUUID(), Money.of("1", "EUR"))))
                .isInstanceOf(UnbalancedJournalEntryException.class);
    }

    @Test
    void rejectsTheSameAccountTwice() {
        var account = UUID.randomUUID();
        assertThatThrownBy(() -> JournalEntry.of(JournalEntryType.ADJUSTMENT, "dup",
                credit(account, Money.of("1", "EUR")),
                debit(account, Money.of("1", "EUR"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void anyBalancedSetOfPostingsIsAccepted(
            @ForAll @Size(min = 1, max = 20) List<@LongRange(min = 1, max = 1_000_000_000L) Long> credits) {
        assertThatCode(() -> new JournalEntry(UUID.randomUUID(), JournalEntryType.TRANSFER, "p", balanced(credits, 0)))
                .doesNotThrowAnyException();
    }

    @Property
    void anyImbalanceIsRejected(
            @ForAll @Size(min = 1, max = 20) List<@LongRange(min = 1, max = 1_000_000_000L) Long> credits,
            @ForAll @LongRange(min = 1, max = 1_000_000L) long imbalance) {
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), JournalEntryType.TRANSFER, "p", balanced(credits, imbalance)))
                .isInstanceOf(UnbalancedJournalEntryException.class);
    }

    /** One credit per amount plus a single debit of the total, increased by {@code imbalance}. */
    private static List<Posting> balanced(List<Long> credits, long imbalance) {
        var postings = new ArrayList<Posting>();
        long total = 0;
        for (long amount : credits) {
            postings.add(credit(UUID.randomUUID(), new Money(amount, EUR)));
            total += amount;
        }
        postings.add(debit(UUID.randomUUID(), new Money(total + imbalance, EUR)));
        return postings;
    }
}
