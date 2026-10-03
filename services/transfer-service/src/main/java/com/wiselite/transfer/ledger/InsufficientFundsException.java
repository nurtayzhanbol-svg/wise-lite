package com.wiselite.transfer.ledger;

import java.util.UUID;

public class InsufficientFundsException extends LedgerException {

    private static final long serialVersionUID = 1L;

    public InsufficientFundsException(UUID accountId, Money available, Money requested) {
        super("Insufficient funds in %s: available %s, requested %s".formatted(accountId, available, requested));
    }
}
