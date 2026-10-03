package com.wiselite.fx;

public class AmountTooSmallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AmountTooSmallException() {
        super("Amount does not cover the fee");
    }
}
