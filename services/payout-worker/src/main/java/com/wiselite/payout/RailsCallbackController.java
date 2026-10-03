package com.wiselite.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiselite.rails.api.RailsApi;
import com.wiselite.rails.api.WebhookSignature;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RailsCallbackController {

    private final CallbackService callbacks;
    private final ObjectMapper json;
    private final String secret;

    public RailsCallbackController(CallbackService callbacks, ObjectMapper json, PayoutProperties properties) {
        this.callbacks = callbacks;
        this.json = json;
        this.secret = properties.callbackSecret();
    }

    /** Raw body as String: the signature is over the exact bytes, not a re-serialised object. */
    @PostMapping("/rails/callbacks")
    public ResponseEntity<Map<String, String>> callback(
            @RequestHeader(value = RailsApi.SIGNATURE_HEADER, required = false) String signature, @RequestBody String body) {
        if (!WebhookSignature.verify(secret, body, signature)) {
            return ResponseEntity.status(401).body(Map.of("error", "bad signature"));
        }
        RailsApi.Callback callback;
        try {
            callback = json.readValue(body, RailsApi.Callback.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "unparseable callback"));
        }
        return ResponseEntity.ok(Map.of("outcome", callbacks.handle(callback).name()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CallbackService.PayoutNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(CallbackService.PayoutNotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }
}
