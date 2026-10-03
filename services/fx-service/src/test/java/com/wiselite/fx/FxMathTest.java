package com.wiselite.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Scale;
import org.junit.jupiter.api.Test;

class FxMathTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Currency JPY = Currency.getInstance("JPY");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void crossRateGoesThroughEur() {
        // 1 EUR = 1.1050 USD = 0.8420 GBP  =>  1 GBP = 1.1050 / 0.8420 USD
        assertThat(FxMath.crossRate(new BigDecimal("0.8420"), new BigDecimal("1.1050"))).isEqualByComparingTo("1.31235154");
    }

    @Test
    void feeIsProportionalButAtLeastTheMinimumAndRoundsUp() {
        var pct = new BigDecimal("0.0043");
        var min = new BigDecimal("0.30");
        assertThat(FxMath.fee(1_000_00, EUR, pct, min)).isEqualTo(430);   // 1000.00 * 0.43% = 4.30
        assertThat(FxMath.fee(10_00, EUR, pct, min)).isEqualTo(30);       // 0.043 -> minimum 0.30
        assertThat(FxMath.fee(1_001_01, EUR, pct, min)).isEqualTo(431);   // 4.304343 -> 4.31 (up)
        assertThat(FxMath.fee(1_000, JPY, pct, min)).isEqualTo(5);        // 4.3 -> 5 yen
    }

    @Test
    void conversionRoundsDownToTargetMinorUnits() {
        assertThat(FxMath.convert(100_00, EUR, USD, new BigDecimal("1.10509999"))).isEqualTo(110_50);
        assertThat(FxMath.convert(100_00, EUR, JPY, new BigDecimal("161.209"))).isEqualTo(16_120);
    }

    @Test
    void parsingRejectsSubMinorPrecision() {
        assertThat(FxMath.toMinor(new BigDecimal("12.3"), EUR)).isEqualTo(1230);
        assertThatThrownBy(() -> FxMath.toMinor(new BigDecimal("1.5"), JPY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxMath.toMinor(new BigDecimal("1.001"), GBP)).isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void convertedAmountNeverExceedsTheExactValueAndIsWithinOneMinorUnit(
            @ForAll @LongRange(min = 1, max = 1_000_000_000_00L) long sourceMinor,
            @ForAll @BigRange(min = "0.0001", max = "50000") @Scale(8) BigDecimal rawRate) {
        var rate = rawRate.setScale(FxMath.RATE_SCALE, RoundingMode.HALF_EVEN);
        long target = FxMath.convert(sourceMinor, EUR, USD, rate);
        var exact = BigDecimal.valueOf(sourceMinor, 2).multiply(rate).movePointRight(2);

        assertThat(BigDecimal.valueOf(target)).isLessThanOrEqualTo(exact);
        assertThat(exact.subtract(BigDecimal.valueOf(target))).isLessThan(BigDecimal.ONE);
    }

    @Property
    void feeIsAlwaysAtLeastTheProportionalFee(@ForAll @LongRange(min = 1, max = 1_000_000_000_00L) long sourceMinor) {
        long fee = FxMath.fee(sourceMinor, EUR, new BigDecimal("0.0043"), new BigDecimal("0.30"));
        assertThat(BigDecimal.valueOf(fee)).isGreaterThanOrEqualTo(BigDecimal.valueOf(sourceMinor).multiply(new BigDecimal("0.0043")));
        assertThat(fee).isGreaterThanOrEqualTo(30);
    }
}
