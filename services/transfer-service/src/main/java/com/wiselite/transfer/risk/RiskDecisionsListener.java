package com.wiselite.transfer.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wiselite.events.RiskDecision;
import com.wiselite.events.Topics;
import java.util.Set;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RiskDecisionsListener {

    private static final Set<String> DECISIONS = Set.of(RiskDecision.ALLOW, RiskDecision.REVIEW, RiskDecision.BLOCK);

    private final RiskDecisionHandler handler;
    private final ObjectMapper json;

    public RiskDecisionsListener(RiskDecisionHandler handler, ObjectMapper json) {
        this.handler = handler;
        this.json = json;
    }

    /** Malformed decisions are not retryable: IllegalArgumentException sends them to the DLT. */
    @KafkaListener(topics = Topics.RISK_DECISIONS)
    public void onEvent(String payload) {
        RiskDecision decision;
        try {
            decision = json.readValue(payload, RiskDecision.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unparseable risk decision", e);
        }
        if (decision.decisionId() == null || decision.transferId() == null || decision.decidedAt() == null
                || !DECISIONS.contains(decision.decision())) {
            throw new IllegalArgumentException("Invalid risk decision " + payload);
        }
        handler.handle(decision);
    }
}
