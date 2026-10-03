package com.wiselite.fx;

public class RatesUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RatesUnavailableException() {
        super("Exchange rates are temporarily unavailable");
    }
}
