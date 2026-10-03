package com.wiselite.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;

public record Quote(
        UUID id,
        Currency source,
        Currency target,
        long sourceAmountMinor,
        long feeMinor,
        BigDecimal rate,
        long targetAmountMinor,
        LocalDate rateAsOf,
        Instant createdAt,
        Instant expiresAt,
        Instant usedAt,
        String usedBy) {

    public enum Status { OPEN, EXPIRED, USED }

    public Status status(Instant now) {
        if (usedAt != null) {
            return Status.USED;
        }
        return now.isBefore(expiresAt) ? Status.OPEN : Status.EXPIRED;
    }
}
