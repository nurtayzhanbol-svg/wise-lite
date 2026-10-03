package com.wiselite.fx;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Quotes lock a rate for {@code quoteTtl}. A quote can be consumed once (by one transfer);
 * consuming it again with the same reference is an idempotent no-op.
 */
@Service
public class QuoteService {

    private final JdbcClient jdbc;
    private final RateService rates;
    private final Clock clock;
    private final FxProperties properties;

    public QuoteService(JdbcClient jdbc, RateService rates, Clock clock, FxProperties properties) {
        this.jdbc = jdbc;
        this.rates = rates;
        this.clock = clock;
        this.properties = properties;
    }

    public Quote create(Currency source, Currency target, BigDecimal sourceAmount) {
        if (source.equals(target)) {
            throw new IllegalArgumentException("Source and target currency must differ");
        }
        long sourceMinor = FxMath.toMinor(sourceAmount, source);
        if (sourceMinor <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        var snapshot = rates.current();
        var rate = snapshot.rate(source, target);
        long fee = FxMath.fee(sourceMinor, source, properties.feePercentage(), properties.feeMinimum());
        if (fee >= sourceMinor) {
            throw new AmountTooSmallException();
        }
        long targetMinor = FxMath.convert(sourceMinor - fee, source, target, rate);
        if (targetMinor <= 0) {
            throw new AmountTooSmallException();
        }

        var now = Instant.now(clock);
        var quote = new Quote(UUID.randomUUID(), source, target, sourceMinor, fee, rate, targetMinor, snapshot.asOf(),
                now, now.plus(properties.quoteTtl()), null, null);
        jdbc.sql("""
                        INSERT INTO quotes (id, source_currency, target_currency, source_amount_minor, fee_minor, rate,
                                            target_amount_minor, rate_as_of, created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(quote.id(), source.getCurrencyCode(), target.getCurrencyCode(), sourceMinor, fee, rate, targetMinor,
                        snapshot.asOf(), Timestamp.from(now), Timestamp.from(quote.expiresAt()))
                .update();
        return quote;
    }

    public Quote get(UUID id) {
        return jdbc.sql("SELECT * FROM quotes WHERE id = ?").param(id)
                .query((rs, n) -> new Quote(
                        rs.getObject("id", UUID.class),
                        Currency.getInstance(rs.getString("source_currency")),
                        Currency.getInstance(rs.getString("target_currency")),
                        rs.getLong("source_amount_minor"),
                        rs.getLong("fee_minor"),
                        rs.getBigDecimal("rate"),
                        rs.getLong("target_amount_minor"),
                        rs.getDate("rate_as_of").toLocalDate(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("used_at") == null ? null : rs.getTimestamp("used_at").toInstant(),
                        rs.getString("used_by")))
                .optional()
                .orElseThrow(() -> new QuoteNotFoundException(id));
    }

    /**
     * Single-use, enforced by one conditional UPDATE: of N concurrent callers exactly one
     * matches {@code used_at IS NULL}. No read-then-write race.
     */
    public Quote consume(UUID id, String consumerReference) {
        if (consumerReference == null || consumerReference.isBlank()) {
            throw new IllegalArgumentException("consumerReference is required");
        }
        var now = Instant.now(clock);
        int rows = jdbc.sql("UPDATE quotes SET used_at = ?, used_by = ? WHERE id = ? AND used_at IS NULL AND expires_at > ?")
                .params(Timestamp.from(now), consumerReference, id, Timestamp.from(now))
                .update();
        var quote = get(id);
        if (rows == 1 || consumerReference.equals(quote.usedBy())) {
            return quote; // won, or a retry by the same consumer
        }
        if (quote.usedAt() != null) {
            throw new QuoteAlreadyUsedException(id);
        }
        throw new QuoteExpiredException(id);
    }

    public Instant now() {
        return Instant.now(clock);
    }
}
