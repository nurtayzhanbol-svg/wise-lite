package com.wiselite.transfer.api;

import static com.wiselite.transfer.ledger.Posting.credit;
import static com.wiselite.transfer.ledger.Posting.debit;

import com.wiselite.transfer.idempotency.IdempotencyService;
import com.wiselite.transfer.idempotency.IdempotencyService.StoredResponse;
import com.wiselite.transfer.idempotency.RequestHash;
import com.wiselite.transfer.ledger.AccountService;
import com.wiselite.transfer.ledger.AccountType;
import com.wiselite.transfer.ledger.JournalEntry;
import com.wiselite.transfer.ledger.JournalEntryType;
import com.wiselite.transfer.ledger.LedgerService;
import com.wiselite.transfer.ledger.Money;
import com.wiselite.transfer.transfer.TransferService;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator/simulation endpoints. In M6 the payout worker and bank webhooks replace the manual
 * transfer transitions; top-ups stand in for incoming bank payments.
 */
@RestController
public class InternalController {

    /** Idempotency scope for internal callers (they have no customer identity). */
    static final UUID INTERNAL_OWNER = new UUID(0, 0);

    public record TopUpRequest(BigDecimal amount, String currency) {}

    public record FailRequest(String reason) {}

    private final TransferService transfers;
    private final TransferController transferViews;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;

    public InternalController(TransferService transfers, TransferController transferViews, AccountService accounts,
            LedgerService ledger, IdempotencyService idempotency) {
        this.transfers = transfers;
        this.transferViews = transferViews;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    @PostMapping("/internal/accounts/{id}/top-ups")
    public ResponseEntity<String> topUp(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @RequestBody TopUpRequest request) {
        if (request.amount() == null || request.currency() == null) {
            throw new IllegalArgumentException("amount and currency are required");
        }
        var amount = Money.of(request.amount().toPlainString(), request.currency());
        if (amount.minor() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        var hash = RequestHash.of("POST", "/internal/accounts/" + id + "/top-ups", amount.minor() + "|" + request.currency());
        var response = idempotency.execute(INTERNAL_OWNER, key, hash, () -> {
            var funding = accounts.systemAccount(AccountType.EXTERNAL_FUNDING, amount.currency());
            ledger.post(JournalEntry.of(JournalEntryType.TOP_UP, "topup:" + key,
                    debit(funding.id(), amount), credit(id, amount)));
            return new StoredResponse(201, transferViews.toJson(MoneyViewHolder.of(accounts.balance(id))), false);
        });
        return TransferController.respond(response);
    }

    @PostMapping("/internal/transfers/{id}/processing")
    public TransferController.TransferView markProcessing(@PathVariable UUID id) {
        return transferViews.view(transfers.markProcessing(id));
    }

    @PostMapping("/internal/transfers/{id}/complete")
    public TransferController.TransferView complete(@PathVariable UUID id) {
        return transferViews.view(transfers.complete(id));
    }

    @PostMapping("/internal/transfers/{id}/fail")
    public TransferController.TransferView fail(@PathVariable UUID id, @RequestBody FailRequest request) {
        return transferViews.view(transfers.fail(id, request.reason()));
    }

    record MoneyViewHolder(AccountController.MoneyView balance) {
        static MoneyViewHolder of(Money m) {
            return new MoneyViewHolder(AccountController.MoneyView.of(m));
        }
    }
}
