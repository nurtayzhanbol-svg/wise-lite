package com.wiselite.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;

/** One day's ECB reference rates, expressed as 1 EUR = X. */
public record RateSnapshot(LocalDate asOf, Map<String, BigDecimal> perEur, Instant fetchedAt) {

    public RateSnapshot {
        perEur = Map.copyOf(perEur);
    }

    public BigDecimal rate(Currency source, Currency target) {
        return FxMath.crossRate(perEur(source), perEur(target));
    }

    private BigDecimal perEur(Currency c) {
        var r = perEur.get(c.getCurrencyCode());
        if (r == null) {
            throw new UnsupportedCurrencyException(c);
        }
        return r;
    }

    public RateSnapshot fetchedAt(Instant at) {
        return new RateSnapshot(asOf, perEur, at);
    }
}
