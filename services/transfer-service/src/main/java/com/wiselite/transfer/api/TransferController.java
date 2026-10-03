package com.wiselite.transfer.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiselite.transfer.api.AccountController.MoneyView;
import com.wiselite.transfer.idempotency.IdempotencyService;
import com.wiselite.transfer.idempotency.IdempotencyService.StoredResponse;
import com.wiselite.transfer.idempotency.RequestHash;
import com.wiselite.transfer.ledger.Money;
import com.wiselite.transfer.transfer.Transfer;
import com.wiselite.transfer.transfer.TransferNotFoundException;
import com.wiselite.transfer.transfer.TransferService;
import com.wiselite.transfer.transfer.TransferService.CreateTransfer;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer-facing transfer API. {@code X-Owner-Id} stands in for the authenticated customer
 * (no auth in this project); every lookup is scoped to it.
 */
@RestController
public class TransferController {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    public record RecipientRequest(String name, String iban) {}

    public record CreateTransferRequest(UUID sourceAccountId, BigDecimal amount, String currency, RecipientRequest recipient) {}

    public record StateChangeView(String from, String to, String reason, Instant at) {}

    public record TransferView(UUID id, String state, UUID sourceAccountId, MoneyView amount,
            RecipientRequest recipient, Instant createdAt, List<StateChangeView> history) {}

    private final TransferService transfers;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public TransferController(TransferService transfers, IdempotencyService idempotency, ObjectMapper json) {
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping("/transfers")
    public ResponseEntity<String> create(
            @RequestHeader("X-Owner-Id") UUID ownerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateTransferRequest request) {
        var command = toCommand(request);
        // Hash the parsed command, not raw bytes: "10.0" and "10.00" are the same request.
        var hash = RequestHash.of("POST", "/transfers", String.join("|",
                command.sourceAccountId().toString(), Long.toString(command.amount().minor()),
                command.amount().currency().getCurrencyCode(), command.recipient().name(), command.recipient().iban()));

        var response = idempotency.execute(ownerId, idempotencyKey, hash, () -> {
            var transfer = transfers.create(ownerId, command);
            return new StoredResponse(201, toJson(view(transfer)), false);
        });
        return respond(response);
    }

    @GetMapping("/transfers/{id}")
    public TransferView get(@RequestHeader("X-Owner-Id") UUID ownerId, @PathVariable UUID id) {
        var transfer = transfers.get(id);
        if (!transfer.ownerId().equals(ownerId)) {
            throw new TransferNotFoundException(id);
        }
        return view(transfer);
    }

    static ResponseEntity<String> respond(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header(REPLAYED_HEADER, Boolean.toString(response.replayed()))
                .body(response.body());
    }

    TransferView view(Transfer t) {
        var history = transfers.history(t.id()).stream()
                .map(h -> new StateChangeView(h.from() == null ? null : h.from().name(), h.to().name(), h.reason(), h.at()))
                .toList();
        return new TransferView(t.id(), t.state().name(), t.sourceAccountId(), MoneyView.of(t.amount()),
                new RecipientRequest(t.recipient().name(), t.recipient().iban()), t.createdAt(), history);
    }

    String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CreateTransfer toCommand(CreateTransferRequest r) {
        if (r.sourceAccountId() == null || r.amount() == null || r.currency() == null || r.recipient() == null
                || isBlank(r.recipient().name()) || isBlank(r.recipient().iban())) {
            throw new IllegalArgumentException("sourceAccountId, amount, currency and recipient{name, iban} are required");
        }
        var money = Money.of(r.amount().toPlainString(), r.currency());
        return new CreateTransfer(r.sourceAccountId(), money,
                new Transfer.Recipient(r.recipient().name().strip(), r.recipient().iban().replace(" ", "").toUpperCase()));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
