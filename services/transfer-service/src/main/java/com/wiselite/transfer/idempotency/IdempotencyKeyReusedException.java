package com.wiselite.transfer.idempotency;

public class IdempotencyKeyReusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request");
    }
}
