package com.wiselite.transfer.transfer;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of a payout transfer.
 *
 * <pre>
 *                      ALLOW / release
 * CREATED ──► FUNDED ──────────────────► APPROVED ──► PROCESSING ──► COMPLETED
 *               │  \ REVIEW / timeout      ▲  │            │
 *               │   └──► HELD ─ release ───┘  │            │
 *               │         │ reject            │            │
 *         BLOCK └────────►├◄──────────────────┴────────────┘ payout rejected
 *                         ▼
 *                       FAILED ──► REFUNDED
 * </pre>
 *
 * Money: FUNDED moves it from the customer to PAYOUT_CLEARING; COMPLETED moves it out to the
 * bank (EXTERNAL_FUNDING); REFUNDED moves it back to the customer. FUNDED, HELD, APPROVED and
 * PROCESSING all keep the money in clearing.
 *
 * <p>Risk gate (ADR 0017): only the transition into APPROVED makes a payout exist (payout-worker
 * reacts to {@code toState=APPROVED} only). There is no edge from FUNDED or HELD to PROCESSING, and no
 * edge back from APPROVED to HELD: an approval is never "cancelled" after the payout may have started.
 */
public enum TransferState {
    CREATED,
    FUNDED,
    HELD,
    APPROVED,
    PROCESSING,
    COMPLETED,
    FAILED,
    REFUNDED;

    private static final Map<TransferState, Set<TransferState>> ALLOWED = Map.of(
            CREATED, EnumSet.of(FUNDED),
            FUNDED, EnumSet.of(APPROVED, HELD, FAILED),
            HELD, EnumSet.of(APPROVED, FAILED),
            APPROVED, EnumSet.of(PROCESSING, FAILED),
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
