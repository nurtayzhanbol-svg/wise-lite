package com.wiselite.fx;

public class UnsupportedCurrencyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnsupportedCurrencyException(java.util.Currency currency) {
        super("Unsupported currency: " + currency);
    }
}
