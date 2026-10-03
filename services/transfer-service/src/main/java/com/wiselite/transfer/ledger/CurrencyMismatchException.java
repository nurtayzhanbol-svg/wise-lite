package com.wiselite.transfer.ledger;

import java.util.Currency;

public class CurrencyMismatchException extends LedgerException {

    private static final long serialVersionUID = 1L;

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("Currency mismatch: expected %s but got %s".formatted(expected, actual));
    }
}
