package com.wiselite.recon;

/** Every kind of disagreement the job looks for, with how bad it is. */
public enum BreakType {
    // Ledger self-checks (one consistent snapshot of the transfers database).
    /** Sum of all ledger entries per currency is not zero: money created or destroyed. */
    TRIAL_BALANCE_NONZERO(Severity.CRITICAL),
    /** account_balances projection differs from the sum of the account's entries. */
    PROJECTION_DRIFT(Severity.CRITICAL),
    /** PAYOUT_CLEARING balance differs from the total of transfers in flight. */
    CLEARING_MISMATCH(Severity.CRITICAL),

    // Our books vs the rail's statement.
    /** The rail has a payment we have no transfer for. */
    UNKNOWN_AT_RAIL(Severity.CRITICAL),
    /** The rail has two payments for one transfer (idempotency broken somewhere). */
    DUPLICATE_AT_RAIL(Severity.CRITICAL),
    /** Amount or currency differs between our transfer and the rail payment. */
    AMOUNT_MISMATCH(Severity.CRITICAL),
    /** We refunded the customer but the rail paid (or may still pay) the recipient. */
    PAID_BUT_REFUNDED(Severity.CRITICAL),
    /** We completed the transfer but the rail has no payment for it. */
    MISSING_AT_RAIL(Severity.HIGH),
    /** We completed the transfer but the rail did not settle it. */
    COMPLETED_BUT_NOT_SETTLED(Severity.HIGH),
    /** Transfer still in flight although the rail already has a final outcome (e.g. lost webhook). */
    STUCK_RESOLVABLE(Severity.MEDIUM),
    /** Transfer funded long ago but payout-worker never recorded a payout (lost event?). */
    MISSING_PAYOUT(Severity.MEDIUM);

    public enum Severity { CRITICAL, HIGH, MEDIUM }

    private final Severity severity;

    BreakType(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }
}
