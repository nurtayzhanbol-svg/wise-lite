package com.wiselite.transfer.transfer;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TransferNotFoundException(UUID id) {
        super("Transfer not found: " + id);
    }
}
