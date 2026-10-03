package com.wiselite.transfer.transfer;

import java.util.UUID;

public class IllegalStateTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IllegalStateTransitionException(UUID transferId, TransferState from, TransferState to) {
        super("Transfer %s cannot go from %s to %s".formatted(transferId, from, to));
    }
}
