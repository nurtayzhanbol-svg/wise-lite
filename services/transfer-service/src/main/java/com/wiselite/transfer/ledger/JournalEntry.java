package com.wiselite.transfer.ledger;

import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A ledger transaction: a set of postings that moves money atomically.
 *
 * <p>Invariant (double-entry): for every currency, the postings sum to zero. Money is never
 * created or destroyed, only moved. An FX conversion is two balanced legs, one per currency:
 * <pre>
 *   customer EUR  -100.00    FX pool EUR  +100.00
 *   FX pool USD   -108.00    customer USD +108.00
 * </pre>
 */
public record JournalEntry(UUID id, JournalEntryType type, String reference, List<Posting> postings) {

    public JournalEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reference, "reference");
        postings = List.copyOf(postings);
        if (postings.size() < 2) {
            throw new UnbalancedJournalEntryException("A journal entry needs at least two postings");
        }
        var accounts = new HashSet<UUID>();
        for (var p : postings) {
            if (!accounts.add(p.accountId())) {
                throw new IllegalArgumentException("Account appears twice in one journal entry: " + p.accountId());
            }
        }
        Map<Currency, Money> sums = new HashMap<>();
        for (var p : postings) {
            sums.merge(p.amount().currency(), p.amount(), Money::plus);
        }
        sums.values().stream()
                .filter(sum -> !sum.isZero())
                .findFirst()
                .ifPresent(sum -> {
                    throw new UnbalancedJournalEntryException("Postings do not balance: net " + sum);
                });
    }

    public static JournalEntry of(JournalEntryType type, String reference, Posting... postings) {
        return new JournalEntry(UUID.randomUUID(), type, reference, List.of(postings));
    }
}
