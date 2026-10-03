package com.wiselite.payout;

/** The record can never be processed (bad JSON, missing fields). Retrying is pointless: dead-letter it. */
public class MalformedEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
