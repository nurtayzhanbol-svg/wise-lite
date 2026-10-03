package com.wiselite.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiselite.events.Topics;
import com.wiselite.events.TransferStateChanged;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransferEventsListener {

    private static final Logger log = LoggerFactory.getLogger(TransferEventsListener.class);

    private final PayoutService payouts;
    private final ObjectMapper json;

    public TransferEventsListener(PayoutService payouts, ObjectMapper json) {
        this.payouts = payouts;
        this.json = json;
    }

    @KafkaListener(topics = Topics.TRANSFER_EVENTS)
    public void onEvent(ConsumerRecord<String, String> record) {
        var event = parse(record);
        var outcome = payouts.handle(event);
        log.debug("transfer={} state={} outcome={}", event.transferId(), event.toState(), outcome);
    }

    private TransferStateChanged parse(ConsumerRecord<String, String> record) {
        try {
            var event = json.readValue(record.value(), TransferStateChanged.class);
            if (event.eventId() == null || event.transferId() == null || event.toState() == null) {
                throw new IllegalArgumentException("missing required fields");
            }
            return event;
        } catch (Exception e) {
            throw new MalformedEventException("Unparseable event at " + record.topic() + "-" + record.partition() + "@" + record.offset(), e);
        }
    }
}
