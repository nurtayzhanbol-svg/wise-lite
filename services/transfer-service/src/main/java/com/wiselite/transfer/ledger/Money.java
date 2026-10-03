package com.wiselite.transfer.ledger;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in the currency's minor unit (cents for EUR, yen for JPY).
 *
 * <p>Stored as a {@code long} so arithmetic is exact and overflow is detected
 * ({@link Math#addExact}). Never use {@code double} for money: 0.1 + 0.2 != 0.3.
 */
public record Money(long minor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Not a monetary currency: " + currency);
        }
    }

    /** Parses a decimal amount, rejecting more precision than the currency has (e.g. 1.005 EUR). */
    public static Money of(String amount, String currencyCode) {
        var currency = Currency.getInstance(currencyCode);
        var scaled = new BigDecimal(amount).movePointRight(currency.getDefaultFractionDigits());
        try {
            return new Money(scaled.longValueExact(), currency);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount %s has too many decimals for %s".formatted(amount, currencyCode), e);
        }
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minor), currency);
    }

    public boolean isZero() {
        return minor == 0;
    }

    public boolean isNegative() {
        return minor < 0;
    }

    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(minor, currency.getDefaultFractionDigits());
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return toDecimal().toPlainString() + " " + currency.getCurrencyCode();
    }
}
