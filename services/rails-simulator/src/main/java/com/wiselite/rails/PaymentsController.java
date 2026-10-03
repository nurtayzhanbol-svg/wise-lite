package com.wiselite.rails;

import com.wiselite.rails.api.RailsApi;
import com.wiselite.rails.api.RailsApi.PaymentRequest;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PaymentsController {

    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final AtomicReference<Faults> faults = new AtomicReference<>(Faults.none());
    private final Settlement settlement;
    private final Random random;

    PaymentsController(Settlement settlement, Random random) {
        this.settlement = settlement;
        this.random = random;
    }

    @PostMapping("/payments")
    ResponseEntity<Object> submit(
            @RequestHeader(value = RailsApi.IDEMPOTENCY_KEY, required = false) String key, @RequestBody PaymentRequest request)
            throws InterruptedException {
        var f = faults.get();
        if (f.latencyMs() > 0) {
            Thread.sleep(f.latencyMs());
        }
        if (key == null || key.isBlank()) {
            return error(400, "Idempotency-Key header is required");
        }
        if (random.nextDouble() < f.failBeforeRate()) {
            return error(500, "injected: failed before processing");
        }
        var invalid = validate(request);
        if (invalid != null) {
            return error(400, invalid);
        }

        var created = new AtomicBoolean();
        var payment = payments.computeIfAbsent(key, k -> {
            created.set(true);
            return new Payment(request);
        });
        if (!payment.request().equals(request)) {
            return error(422, "Idempotency-Key reused with a different request");
        }
        if (created.get()) {
            settlement.schedule(payment, f);
        }
        if (random.nextDouble() < f.failAfterRate()) {
            return error(500, "injected: failed after processing"); // the payment exists anyway
        }
        return ResponseEntity.status(created.get() ? 202 : 200).body(payment.view());
    }

    @GetMapping("/payments/{key}")
    ResponseEntity<Object> get(@PathVariable String key) {
        var payment = payments.get(key);
        return payment == null ? error(404, "unknown payment") : ResponseEntity.ok(payment.view());
    }

    @GetMapping("/admin/faults")
    Faults faults() {
        return faults.get();
    }

    @PostMapping("/admin/faults")
    Faults setFaults(@RequestBody Faults newFaults) {
        faults.set(newFaults);
        return newFaults;
    }

    @PostMapping("/admin/reset")
    void reset() {
        payments.clear();
        faults.set(Faults.none());
    }

    private static String validate(PaymentRequest r) {
        if (r.reference() == null || r.reference().isBlank()) {
            return "reference is required";
        }
        if (r.amountMinor() <= 0) {
            return "amountMinor must be positive";
        }
        if (r.currency() == null || !r.currency().matches("[A-Z]{3}")) {
            return "currency must be an ISO 4217 code";
        }
        if (!Iban.isValid(r.recipientIban())) {
            return "invalid IBAN";
        }
        return null;
    }

    private static ResponseEntity<Object> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
