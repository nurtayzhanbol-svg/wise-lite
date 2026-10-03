# FX quotes (M5)

Code: `fx-service/.../FxMath`, `RateService`, `QuoteService`, `EcbRateProvider`. Tests: `FxMathTest` (incl. jqwik), `RateServiceTest`, `EcbRateProviderTest`, `QuoteApiIT`.

## 1. What to be able to explain
1. **Why quotes exist.** Rates move every second. The customer must see a guaranteed amount before committing, so we lock a rate for N seconds and take the FX risk for that window. Shorter TTL = less risk for us, but more re-quoting for users.
2. **Cross rates.** With an EUR-based source, GBP→USD = EUR→USD / EUR→GBP. Know how the precision of the division matters, and why you fix the rate scale.
3. **Where rounding happens, and in whose favour.** Every rounding is a policy (ADR 0012). Property test: the converted amount ≤ the exact value, and within 1 minor unit of it.
4. **Minor units differ:** JPY 0, EUR 2, BHD 3. `1.5 JPY` is invalid input → 400.
5. **Single-use under concurrency:** a conditional UPDATE, 12 concurrent consumers → exactly 1 winner (`concurrentConsumersExactlyOneWins`).
6. **Idempotent consume:** the same transfer retrying gets 200, not 409. Otherwise a network retry after a successful consume would fail the transfer.
7. **Degraded mode and fail-closed:** serve slightly stale rates for a bounded time, then 503 + `Retry-After`. Know the trade-off: availability vs financial risk.
8. **Testing time:** an injected `Clock` and a `MutableClock` in tests mean no `Thread.sleep` in expiry/staleness tests.
9. **Stampede protection:** `refresh()` is `synchronized`, so when the snapshot goes stale, 1000 requests don't each call the provider.
10. **Security:** parsing external XML → disable DTDs (XXE). Outbound calls → always set timeouts.

## 2. Likely questions
- "Rates change during the transfer, who pays?" → We do, within the TTL. That is why the TTL is short, and why a fee/spread exists.
- "Provider down for a day?" → After `max-staleness` we refuse quotes. Mitigations: a second provider (fallback chain), alerts.
- "How do you scale quote creation?" → It is stateless except for the DB insert. Rates are in memory per instance. The quote table can be partitioned by time and pruned.
- "Why is fee rounding up fair?" → It is shown before the customer commits. Transparency matters more than the direction of rounding.

## 3. Rebuild exercise
Write `FxMath.convert` and `fee` yourself from the ADR rules. Make the jqwik properties pass. Then change the target rounding to HALF_UP and find the counterexample jqwik reports.
