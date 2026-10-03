package com.wiselite.transfer.ledger;

import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/** A single-currency account. A customer's "multi-currency balance" is one account per currency. */
public record Account(UUID id, UUID ownerId, Currency currency, AccountType type) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(type, "type");
        if ((type == AccountType.CUSTOMER) != (ownerId != null)) {
            throw new IllegalArgumentException("Customer accounts need an owner; system accounts must not have one");
        }
    }
}
