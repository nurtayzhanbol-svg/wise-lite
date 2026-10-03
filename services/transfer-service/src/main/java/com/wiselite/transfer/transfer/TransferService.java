package com.wiselite.transfer.transfer;

import static com.wiselite.transfer.ledger.Posting.credit;
import static com.wiselite.transfer.ledger.Posting.debit;

import com.wiselite.events.Topics;
import com.wiselite.events.TransferStateChanged;
import com.wiselite.transfer.ledger.AccountNotFoundException;
import com.wiselite.transfer.ledger.AccountService;
import com.wiselite.transfer.ledger.AccountType;
import com.wiselite.transfer.ledger.CurrencyMismatchException;
import com.wiselite.transfer.ledger.JournalEntry;
import com.wiselite.transfer.ledger.JournalEntryType;
import com.wiselite.transfer.ledger.LedgerService;
import com.wiselite.transfer.ledger.Money;
import com.wiselite.transfer.outbox.OutboxWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the transfer lifecycle. Every state change and its ledger movement happen in one
 * database transaction, so the state and the money can never disagree.
 */
@Service
public class TransferService {

    public record CreateTransfer(UUID sourceAccountId, Money amount, Transfer.Recipient recipient) {}

    private final TransferRepository transfers;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final Clock clock;
    private final OutboxWriter outbox;

    public TransferService(TransferRepository transfers, AccountService accounts, LedgerService ledger, Clock clock,
            OutboxWriter outbox) {
        this.outbox = outbox;
        this.transfers = transfers;
        this.accounts = accounts;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** Creates the transfer and reserves the money (CREATED → FUNDED) atomically. */
    @Transactional
    public Transfer create(UUID ownerId, CreateTransfer command) {
        var source = accounts.get(command.sourceAccountId());
        if (!ownerId.equals(source.ownerId())) {
            // Same response as a missing account: don't reveal other customers' account ids.
            throw new AccountNotFoundException(command.sourceAccountId());
        }
        if (!source.currency().equals(command.amount().currency())) {
            throw new CurrencyMismatchException(source.currency(), command.amount().currency());
        }
        if (command.amount().minor() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        var now = Instant.now(clock);
        var transfer = new Transfer(UUID.randomUUID(), ownerId, source.id(), command.amount(), command.recipient(),
                TransferState.CREATED, now, now);
        transfers.insert(transfer);
        transfers.appendHistory(transfer.id(), null, TransferState.CREATED, null);

        return advance(transfer.id(), TransferState.FUNDED, null, t -> ledger.post(JournalEntry.of(
                JournalEntryType.TRANSFER, journalReference(t, "fund"),
                debit(t.sourceAccountId(), t.amount()),
                credit(clearing(t), t.amount()))));
    }

    @Transactional
    public Transfer markProcessing(UUID id) {
        return advance(id, TransferState.PROCESSING, null, t -> {});
    }

    /** The bank confirmed the payout: money leaves clearing for the outside world. */
    @Transactional
    public Transfer complete(UUID id) {
        return advance(id, TransferState.COMPLETED, null, t -> ledger.post(JournalEntry.of(
                JournalEntryType.PAYOUT, journalReference(t, "payout"),
                debit(clearing(t), t.amount()),
                credit(accounts.systemAccount(AccountType.EXTERNAL_FUNDING, t.amount().currency()).id(), t.amount()))));
    }

    /** The payout failed: FAILED, then refund the customer (REFUNDED) in the same transaction. */
    @Transactional
    public Transfer fail(UUID id, String reason) {
        var current = transfers.find(id).orElseThrow(() -> new TransferNotFoundException(id));
        if (current.state() == TransferState.REFUNDED) {
            return current;
        }
        advance(id, TransferState.FAILED, reason, t -> {});
        return advance(id, TransferState.REFUNDED, null, t -> ledger.post(JournalEntry.of(
                JournalEntryType.REFUND, journalReference(t, "refund"),
                debit(clearing(t), t.amount()),
                credit(t.sourceAccountId(), t.amount()))));
    }

    public Transfer get(UUID id) {
        return transfers.find(id).orElseThrow(() -> new TransferNotFoundException(id));
    }

    public List<Transfer.StateChange> history(UUID id) {
        return transfers.history(id);
    }

    /**
     * Moves a transfer to {@code target}. Requesting the state it is already in is a no-op,
     * so duplicate events (retried requests, duplicate webhooks) are harmless.
     */
    private Transfer advance(UUID id, TransferState target, String reason, Consumer<Transfer> sideEffect) {
        var current = transfers.lock(id).orElseThrow(() -> new TransferNotFoundException(id));
        if (current.state() == target) {
            return current;
        }
        if (!current.state().canTransitionTo(target)) {
            throw new IllegalStateTransitionException(id, current.state(), target);
        }
        sideEffect.accept(current);
        transfers.updateState(id, target);
        transfers.appendHistory(id, current.state(), target, reason);
        // Same transaction as the state change: the event exists if and only if the change committed.
        var event = new TransferStateChanged(UUID.randomUUID(), id, current.ownerId(), current.state().name(), target.name(),
                current.amount().minor(), current.amount().currency().getCurrencyCode(), current.recipient().name(),
                current.recipient().iban(), reason, Instant.now(clock));
        outbox.append(Topics.TRANSFER_EVENTS, id.toString(), TransferStateChanged.TYPE, event.eventId(), event);
        return current.withState(target);
    }

    private UUID clearing(Transfer t) {
        return accounts.systemAccount(AccountType.PAYOUT_CLEARING, t.amount().currency()).id();
    }

    private static String journalReference(Transfer t, String step) {
        return "transfer:" + t.id() + ":" + step;
    }
}
