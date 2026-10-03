package com.wiselite.payout;

import com.wiselite.rails.api.RailsApi;
import com.wiselite.rails.api.RailsApi.PaymentRequest;
import com.wiselite.rails.api.RailsApi.PaymentResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Classifies every rail response into what it means for the money:
 * definitely accepted, definitely rejected, needs a human, or <b>unknown</b>.
 */
@Component
public class RailsClient {

    public sealed interface Result permits Accepted, Rejected, Anomaly, Unknown {}

    /** The rail has the payment; status may already be final on an idempotent replay. */
    public record Accepted(String paymentId, String status) implements Result {}

    /** 400: the rail refused it; no money moved. Safe to fail the transfer. */
    public record Rejected(String reason) implements Result {}

    /** 409/422 etc.: contract violation (e.g. key reused with another body). A human must look. */
    public record Anomaly(String reason) implements Result {}

    /** Timeout, connection reset, 5xx, 429: money may or may not have moved. Retry with the same key. */
    public record Unknown(String reason) implements Result {}

    private final RestClient http;
    private final PayoutProperties properties;
    private final MeterRegistry meters;

    public RailsClient(RestClient.Builder builder, PayoutProperties properties, MeterRegistry meters) {
        this.meters = meters;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.http = builder.requestFactory(factory).baseUrl(properties.railsUrl().toString()).build();
        this.properties = properties;
    }

    public Result submit(PayoutStore.ClaimedPayout payout) {
        var sample = Timer.start(meters);
        var result = doSubmit(payout);
        sample.stop(Timer.builder("wiselite.rails.calls").tag("outcome", result.getClass().getSimpleName())
                .publishPercentileHistogram().register(meters));
        return result;
    }

    private Result doSubmit(PayoutStore.ClaimedPayout payout) {
        var request = new PaymentRequest(payout.transferId().toString(), payout.amountMinor(), payout.currency(),
                payout.recipientName(), payout.recipientIban(), properties.callbackUrl());
        try {
            var response = http.post().uri("/payments")
                    // The transfer id is the idempotency key: every retry of this payout is the same payment.
                    .header(RailsApi.IDEMPOTENCY_KEY, payout.transferId().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(PaymentResponse.class);
            if (response == null || response.paymentId() == null) {
                return new Unknown("empty response");
            }
            return new Accepted(response.paymentId(), response.status());
        } catch (HttpClientErrorException e) {
            int code = e.getStatusCode().value();
            if (code == 408 || code == 429) {
                return new Unknown(code + " " + e.getResponseBodyAsString());
            }
            if (code == 400) {
                return new Rejected(e.getResponseBodyAsString());
            }
            return new Anomaly(code + " " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            return new Unknown(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
