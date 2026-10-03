# ADR 0012: FX quotes, rate source, rounding and degraded mode

## Status
Accepted (M5)

## Decisions
1. **Rate source:** ECB daily reference rates (EUR base, free, published once per working day). Cross rates are computed as `(EUR→target) / (EUR→source)` at 8 decimal places, HALF_EVEN. *Simplification:* real FX uses live mid-market rates from several providers.
2. **Quotes lock a rate for a short TTL (30 s)** and are persisted in the `fx` database. A transfer can execute only against an OPEN quote, so the customer gets exactly the amount they were shown.
3. **Single use:** `UPDATE quotes SET used_at, used_by WHERE id = ? AND used_at IS NULL AND expires_at > now`, a conditional write (see M3). The same `consumerReference` consuming again gets 200, which keeps retries by the same transfer idempotent. Another consumer gets 409, an expired quote gets 410.
4. **Rounding:** the fee rounds **up** to the source minor unit, and the converted amount rounds **down** to the target minor unit. Rounding never makes us pay out more than the exact conversion. Each rounding is visible in the quote response.
5. **Fee:** `max(amount × 0.43%, 0.30 source units)`, shown separately from the rate (no hidden markup). *Simplification:* real fees vary by currency route.
6. **Degraded mode:** if the provider is down, keep quoting on the last good snapshot for `max-staleness` (1 h), then **fail closed** with 503 + `Retry-After`. Quoting on rates that are too old is a financial risk. Refusing is a product decision that can be made safely.
7. **Outbound HTTP:** explicit connect/read timeouts (2 s / 5 s). The ECB XML is parsed with DTDs disabled, to protect against XXE.

## Alternatives
- **Redis for quotes/rates:** rejected for now. The quotes need durability and a conditional single-use update, which Postgres already gives. One rate snapshot fits in memory. Redis becomes worth it with many instances and real-time rates.
- **Rates as `double`:** never. See ADR 0004.
- **Rounding HALF_EVEN everywhere:** statistically fair, but it can give the customer a fraction more than the exact value, and that fraction comes out of our liquidity.

## TODO
- Use quotes in transfer-service: the cross-currency transfer debits the source and credits the target via `FX_POOL` with a multi-currency journal (already supported by `JournalEntry`).
- Check publication-date staleness (ECB doesn't publish on weekends), in addition to fetch age.
