package com.wiselite.transfer.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiselite.events.PayoutStatusChanged;
import com.wiselite.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PayoutEventsListener {

    private final PayoutOutcomeHandler handler;
    private final ObjectMapper json;

    public PayoutEventsListener(PayoutOutcomeHandler handler, ObjectMapper json) {
        this.handler = handler;
        this.json = json;
    }

    @KafkaListener(topics = Topics.PAYOUT_EVENTS)
    public void onEvent(String payload) {
        PayoutStatusChanged event;
        try {
            event = json.readValue(payload, PayoutStatusChanged.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unparseable payout event", e);
        }
        handler.handle(event);
    }
}
