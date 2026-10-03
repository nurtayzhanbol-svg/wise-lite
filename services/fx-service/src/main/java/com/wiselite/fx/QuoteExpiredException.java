package com.wiselite.fx;

public class QuoteExpiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QuoteExpiredException(java.util.UUID id) {
        super("Quote expired: " + id);
    }
}
