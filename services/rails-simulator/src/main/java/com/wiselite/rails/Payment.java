package com.wiselite.rails;

import com.wiselite.rails.api.RailsApi;
import com.wiselite.rails.api.RailsApi.PaymentRequest;
import com.wiselite.rails.api.RailsApi.PaymentResponse;
import java.util.UUID;

final class Payment {

    private final String id = UUID.randomUUID().toString();
    private final PaymentRequest request;
    private String status = RailsApi.PENDING;
    private String eventId;

    Payment(PaymentRequest request) {
        this.request = request;
    }

    PaymentRequest request() {
        return request;
    }

    String id() {
        return id;
    }

    synchronized void complete(String finalStatus) {
        status = finalStatus;
        eventId = UUID.randomUUID().toString();
    }

    synchronized RailsApi.Callback callback() {
        return new RailsApi.Callback(eventId, id, request.reference(), status);
    }

    synchronized PaymentResponse view() {
        return new PaymentResponse(id, request.reference(), status);
    }
}
