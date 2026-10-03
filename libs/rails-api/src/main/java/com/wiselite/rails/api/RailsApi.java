package com.wiselite.rails.api;

/**
 * Contract of the (simulated) payment rail, modelled on real bank/scheme APIs: idempotent
 * submission keyed by {@code Idempotency-Key}, asynchronous outcome via signed webhooks.
 */
public final class RailsApi {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String SIGNATURE_HEADER = "X-Rails-Signature";

    public static final String PENDING = "PENDING";
    public static final String SETTLED = "SETTLED";
    public static final String REJECTED = "REJECTED";

    public record PaymentRequest(
            String reference, long amountMinor, String currency, String recipientName, String recipientIban, String callbackUrl) {}

    public record PaymentResponse(String paymentId, String reference, String status) {}

    /** Webhook body. {@code eventId} is stable across redeliveries of the same notification. */
    public record Callback(String eventId, String paymentId, String reference, String status) {}

    private RailsApi() {}
}
