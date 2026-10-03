package com.wiselite.risk;

import com.wiselite.events.RiskDecision;
import com.wiselite.events.TransferStateChanged;
import java.time.Instant;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * Input keyed by owner (after a repartition), so one task sees all of an owner's transfers and owns their history.
 * Output keyed by transferId, so transfer-service sees a transfer's decision on that transfer's partition.
 *
 * <p>If the transfer is already in the history (reprocessing after a crash between the store update and the
 * commit — impossible with EOS, possible with at-least-once), the original verdict is re-emitted with the same
 * deterministic id instead of being re-evaluated against a history that now contains itself.
 */
final class DecisionProcessor implements Processor<String, TransferStateChanged, String, RiskDecision> {

    private final RiskRules rules;
    private final long horizonMillis;
    private ProcessorContext<String, RiskDecision> context;
    private KeyValueStore<String, OwnerHistory> store;

    DecisionProcessor(RiskRules rules) {
        this.rules = rules;
        this.horizonMillis = Math.max(rules.velocityWindow().toMillis(), rules.volumeWindow().toMillis());
    }

    @Override
    public void init(ProcessorContext<String, RiskDecision> context) {
        this.context = context;
        this.store = context.getStateStore(RiskTopology.OWNER_HISTORY_STORE);
    }

    @Override
    public void process(Record<String, TransferStateChanged> record) {
        var e = record.value();
        var history = store.get(record.key());
        if (history == null) {
            history = OwnerHistory.EMPTY;
        }
        var previous = history.find(e.transferId());
        RiskPolicy.Verdict verdict;
        if (previous != null) {
            verdict = new RiskPolicy.Verdict(previous.decision(), previous.reasons());
        } else {
            verdict = RiskPolicy.decide(rules, history, e, record.timestamp());
            store.put(record.key(), history.with(new OwnerHistory.Entry(e.transferId(), record.timestamp(), e.amountMinor(),
                    e.currency(), verdict.decision(), verdict.reasons()), record.timestamp() - horizonMillis));
        }
        var decision = new RiskDecision(RiskDecision.idFor(e.transferId()), e.transferId(), verdict.decision(),
                verdict.reasons(), RiskPolicy.RULES_VERSION, Instant.ofEpochMilli(record.timestamp()));
        context.forward(new Record<>(e.transferId().toString(), decision, record.timestamp()));
    }
}
