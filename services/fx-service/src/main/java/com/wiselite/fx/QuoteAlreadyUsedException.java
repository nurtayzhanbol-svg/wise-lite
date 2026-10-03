package com.wiselite.fx;

public class QuoteAlreadyUsedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QuoteAlreadyUsedException(java.util.UUID id) {
        super("Quote already used: " + id);
    }
}
