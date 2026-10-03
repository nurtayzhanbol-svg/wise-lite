package com.wiselite.fx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Pure FX arithmetic. Rounding rules (each one is a business decision, see ADR 0012):
 * <ul>
 *   <li>rates: 8 decimal places, HALF_EVEN;</li>
 *   <li>fee: rounded UP to the source currency's minor unit (we never charge a fraction);</li>
 *   <li>target amount: rounded DOWN (we never promise more than the exact conversion).</li>
 * </ul>
 */
public final class FxMath {

    public static final int RATE_SCALE = 8;

    private FxMath() {}

    /** ECB publishes EUR→X. Cross rate source→target = (EUR→target) / (EUR→source). */
    public static BigDecimal crossRate(BigDecimal eurToSource, BigDecimal eurToTarget) {
        return eurToTarget.divide(eurToSource, RATE_SCALE, RoundingMode.HALF_EVEN);
    }

    public static long fee(long sourceMinor, Currency source, BigDecimal percentage, BigDecimal minimumMajor) {
        long proportional = BigDecimal.valueOf(sourceMinor).multiply(percentage).setScale(0, RoundingMode.CEILING).longValueExact();
        long minimum = minimumMajor.movePointRight(digits(source)).setScale(0, RoundingMode.CEILING).longValueExact();
        return Math.max(proportional, minimum);
    }

    public static long convert(long sourceMinor, Currency source, Currency target, BigDecimal rate) {
        var sourceMajor = BigDecimal.valueOf(sourceMinor, digits(source));
        return sourceMajor.multiply(rate).setScale(digits(target), RoundingMode.DOWN).unscaledValue().longValueExact();
    }

    /** Parses a major-unit amount; rejects more decimals than the currency has (e.g. 1.5 JPY). */
    public static long toMinor(BigDecimal major, Currency currency) {
        try {
            return major.setScale(digits(currency), RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Too many decimals for " + currency + ": " + major.toPlainString());
        }
    }

    public static BigDecimal toMajor(long minor, Currency currency) {
        return BigDecimal.valueOf(minor, digits(currency));
    }

    private static int digits(Currency c) {
        return Math.max(0, c.getDefaultFractionDigits());
    }
}
