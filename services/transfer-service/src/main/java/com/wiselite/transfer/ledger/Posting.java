package com.wiselite.transfer.ledger;

import java.util.Objects;
import java.util.UUID;

/**
 * One line of a journal entry. Sign convention: a positive amount increases the account's
 * balance, a negative amount decreases it.
 */
public record Posting(UUID accountId, Money amount) {

    public Posting {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        if (amount.isZero()) {
            throw new IllegalArgumentException("Posting amount must not be zero");
        }
    }

    public static Posting credit(UUID accountId, Money amount) {
        return new Posting(accountId, amount);
    }

    public static Posting debit(UUID accountId, Money amount) {
        return new Posting(accountId, amount.negate());
    }
}
