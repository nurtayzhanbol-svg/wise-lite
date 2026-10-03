package com.wiselite.transfer.ledger;

/**
 * Account types. {@code allowsNegativeBalance} encodes which accounts may go below zero.
 *
 * <p>Simplification: real ledgers distinguish asset/liability normal balances. Here every
 * account's balance is simply the sum of its entries, and only internal accounts may be negative.
 */
public enum AccountType {
    /** Money a customer holds with us, in one currency. Must never be negative. */
    CUSTOMER(false),
    /** Counterpart for money entering/leaving via banks. Goes negative as customers top up. */
    EXTERNAL_FUNDING(true),
    /** Our liquidity pool per currency, used as the counterpart of FX conversions. */
    FX_POOL(true),
    /** Money reserved for transfers in flight: debited from the customer, not yet paid out. */
    PAYOUT_CLEARING(false),
    /** Fees we have earned. */
    FEE_REVENUE(false);

    private final boolean allowsNegativeBalance;

    AccountType(boolean allowsNegativeBalance) {
        this.allowsNegativeBalance = allowsNegativeBalance;
    }

    public boolean allowsNegativeBalance() {
        return allowsNegativeBalance;
    }
}
