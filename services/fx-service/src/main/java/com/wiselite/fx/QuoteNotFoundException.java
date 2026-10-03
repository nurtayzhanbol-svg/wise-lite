package com.wiselite.fx;

public class QuoteNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QuoteNotFoundException(java.util.UUID id) {
        super("Quote not found: " + id);
    }
}
