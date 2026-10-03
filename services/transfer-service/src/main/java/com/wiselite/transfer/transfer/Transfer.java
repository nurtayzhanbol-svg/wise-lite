package com.wiselite.transfer.transfer;

import com.wiselite.transfer.ledger.Money;
import java.time.Instant;
import java.util.UUID;

public record Transfer(
        UUID id,
        UUID ownerId,
        UUID sourceAccountId,
        Money amount,
        Recipient recipient,
        TransferState state,
        Instant createdAt,
        Instant updatedAt) {

    public record Recipient(String name, String iban) {}

    public record StateChange(TransferState from, TransferState to, String reason, Instant at) {}

    public Transfer withState(TransferState newState) {
        return new Transfer(id, ownerId, sourceAccountId, amount, recipient, newState, createdAt, updatedAt);
    }
}
