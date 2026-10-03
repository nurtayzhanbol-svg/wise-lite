package com.wiselite.payout;

import com.wiselite.events.TransferStateChanged;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayoutService {

    public enum Outcome { CREATED, DUPLICATE_EVENT, ALREADY_REQUESTED, IGNORED }

    private final JdbcClient jdbc;

    public PayoutService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent consumer: the dedupe record and the effect commit in one transaction. If the
     * process crashes before the Kafka offset is committed, the redelivered event hits the
     * dedupe row and is skipped.
     */
    @Transactional
    public Outcome handle(TransferStateChanged event) {
        if (!"FUNDED".equals(event.toState())) {
            return Outcome.IGNORED;
        }
        int fresh = jdbc.sql("INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING")
                .param(event.eventId())
                .update();
        if (fresh == 0) {
            return Outcome.DUPLICATE_EVENT;
        }
        int created = jdbc.sql("""
                        INSERT INTO payouts (transfer_id, amount_minor, currency, recipient_name, recipient_iban, status)
                        VALUES (?, ?, ?, ?, ?, 'PENDING')
                        ON CONFLICT (transfer_id) DO NOTHING""")
                .params(event.transferId(), event.amountMinor(), event.currency(), event.recipientName(), event.recipientIban())
                .update();
        return created == 1 ? Outcome.CREATED : Outcome.ALREADY_REQUESTED;
    }

    public long payoutCount(java.util.UUID transferId) {
        return jdbc.sql("SELECT count(*) FROM payouts WHERE transfer_id = ?").param(transferId).query(Long.class).single();
    }
}
