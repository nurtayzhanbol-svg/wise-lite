package com.wiselite.outbox;

public class OutboxPublishException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OutboxPublishException(Throwable cause) {
        super("Failed to publish outbox batch", cause);
    }
}
