package com.wiselite.transfer.transfer;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of a payout transfer.
 *
 * <pre>
 * CREATED ──► FUNDED ──► PROCESSING ──► COMPLETED
 *               │            │
 *               └──► FAILED ◄┘
 *                      │
 *                      ▼
 *                  REFUNDED
 * </pre>
 *
 * Money: FUNDED moves it from the customer to PAYOUT_CLEARING; COMPLETED moves it out to the
 * bank (EXTERNAL_FUNDING); REFUNDED moves it back to the customer.
 */
public enum TransferState {
    CREATED,
    FUNDED,
    PROCESSING,
    COMPLETED,
    FAILED,
    REFUNDED;

    private static final Map<TransferState, Set<TransferState>> ALLOWED = Map.of(
            CREATED, EnumSet.of(FUNDED),
            FUNDED, EnumSet.of(PROCESSING, FAILED),
            PROCESSING, EnumSet.of(COMPLETED, FAILED),
            COMPLETED, EnumSet.noneOf(TransferState.class),
            FAILED, EnumSet.of(REFUNDED),
            REFUNDED, EnumSet.noneOf(TransferState.class));

    public boolean canTransitionTo(TransferState target) {
        return ALLOWED.get(this).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
