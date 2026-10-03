package com.wiselite.transfer.payout;

import com.wiselite.events.PayoutStatusChanged;
import com.wiselite.transfer.transfer.TransferService;
import com.wiselite.transfer.transfer.TransferState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Closes the loop: a final payout outcome completes or refunds the transfer. */
@Service
public class PayoutOutcomeHandler {

    public enum Outcome { APPLIED, DUPLICATE }

    private final JdbcClient jdbc;
    private final TransferService transfers;

    public PayoutOutcomeHandler(JdbcClient jdbc, TransferService transfers) {
        this.jdbc = jdbc;
        this.transfers = transfers;
    }

    /** Dedupe row, state change, ledger entries and the outbox event all commit together. */
    @Transactional
    public Outcome handle(PayoutStatusChanged event) {
        int fresh = jdbc.sql("INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING")
                .param(event.eventId()).update();
        if (fresh == 0) {
            return Outcome.DUPLICATE;
        }
        var id = event.transferId();
        switch (event.status()) {
            case PayoutStatusChanged.SETTLED -> {
                if (transfers.get(id).state() != TransferState.COMPLETED) {
                    transfers.markProcessing(id);
                    transfers.complete(id);
                }
            }
            case PayoutStatusChanged.REJECTED -> transfers.fail(id, "payout rejected: " + event.reason());
            default -> throw new IllegalArgumentException("Unexpected payout status " + event.status());
        }
        return Outcome.APPLIED;
    }
}
