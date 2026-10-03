package com.wiselite.transfer.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void parsesUsingTheCurrencysMinorUnit() {
        assertThat(Money.of("12.34", "EUR").minor()).isEqualTo(1234);
        assertThat(Money.of("100", "JPY").minor()).isEqualTo(100);
        assertThat(Money.of("1.234", "BHD").minor()).isEqualTo(1234);
    }

    @Test
    void rejectsMorePrecisionThanTheCurrencyHas() {
        assertThatThrownBy(() -> Money.of("1.005", "EUR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1.5", "JPY")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToMixCurrencies() {
        assertThatThrownBy(() -> Money.of("1", "EUR").plus(Money.of("1", "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void detectsOverflowInsteadOfWrappingAround() {
        var max = new Money(Long.MAX_VALUE, EUR);
        assertThatThrownBy(() -> max.plus(new Money(1, EUR))).isInstanceOf(ArithmeticException.class);
    }

    @Property
    void plusThenMinusIsIdentity(@ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) long a,
                                 @ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) long b) {
        var x = new Money(a, EUR);
        var y = new Money(b, EUR);
        assertThat(x.plus(y).minus(y)).isEqualTo(x);
    }

    @Property
    void decimalRoundTrip(@ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) long minor) {
        var money = new Money(minor, EUR);
        assertThat(Money.of(money.toDecimal().toPlainString(), "EUR")).isEqualTo(money);
    }
}
