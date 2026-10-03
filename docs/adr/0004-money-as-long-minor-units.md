# ADR-0004: Represent money as a `long` amount in minor units plus a `Currency`

- Status: accepted
- Date: 2026-10-03

## Context
Money needs exact arithmetic, a currency on every amount, and currency-specific precision: EUR has 2 decimals, JPY 0, BHD 3.

## Decision
`Money(long minor, java.util.Currency currency)`:
- The amount is stored as an integer count of the currency's minor unit (`12.34 EUR` → `1234`). The number of decimals comes from `Currency.getDefaultFractionDigits()`.
- Arithmetic uses `Math.addExact` and friends, so overflow throws instead of silently wrapping around.
- Adding or subtracting different currencies throws `CurrencyMismatchException`.
- Parsing rejects more precision than the currency allows (`1.005 EUR` is an error, not rounded silently).
- In Postgres: `BIGINT amount_minor` plus `CHAR(3) currency`.

## Alternatives
- **`double`/`float`:** binary floating point cannot represent 0.10 exactly. Never acceptable for money.
- **`BigDecimal` everywhere:** exact and flexible, but easy to misuse (`equals` treats `2.0` and `2.00` as different, scale handling is manual), and slower. It will be used where fractional precision really is needed: FX rates and fee percentages (M5). Results are rounded back into `Money` explicitly.
- **A library (Joda-Money, JSR-354 Moneta):** good options. A hand-written type is used here so the rules are visible and can be explained in an interview.

## Consequences
- The maximum amount is about 9.2 × 10^16 minor units, far beyond any real balance.
- Currencies with sub-minor-unit pricing (e.g. crypto) would need a different scale. Out of scope.
