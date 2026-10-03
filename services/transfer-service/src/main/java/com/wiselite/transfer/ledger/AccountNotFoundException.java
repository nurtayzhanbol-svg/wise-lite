package com.wiselite.transfer.ledger;

import java.util.UUID;

public class AccountNotFoundException extends LedgerException {

    private static final long serialVersionUID = 1L;

    public AccountNotFoundException(UUID accountId) {
        super("Account not found: " + accountId);
    }
}
