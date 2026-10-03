# ADR 0013: Payout dispatch, unknown outcomes, webhooks, circuit breaker

## Status
Accepted (M6)

## Context
A payout calls a third-party rail over the network. Three outcomes are possible: definitely accepted, definitely refused, and **unknown** (timeout, connection reset, 5xx). In the unknown case the money may already have moved. That case decides whether we pay someone twice, or tell a customer "failed" when the money has actually gone.

## Decisions
1. **Idempotency key = transfer id** on every submission. Retrying an unknown outcome therefore cannot create a second payment. The rail answers a replay with the payment's current status, so "retry" and "check status" are the same call. *If a rail has no idempotency, the fallback is to query the status by our reference before re-submitting.*
2. **Classify the responses** (`RailsClient.Result`):
   - 2xx → `Accepted`;
   - 400 → `Rejected` (safe to fail the transfer);
   - 409/422 → `Anomaly` → MANUAL_REVIEW;
   - 5xx, 408, 429, timeout, I/O error → `Unknown` → retry.
3. **Never auto-fail an unknown.** After `max-attempts` the payout goes to MANUAL_REVIEW, not REJECTED. A late webhook can still resolve it.
4. **No DB transaction across the HTTP call.** Payouts are claimed with a *lease* (`lease_until`) in one short `UPDATE ... FOR UPDATE SKIP LOCKED`. The call happens outside any transaction, and the result is written by another conditional UPDATE. The lease must exceed connect + read timeout.
5. **Every transition is a conditional UPDATE guarded by status.** A late HTTP response can't overwrite a webhook that already settled the payout (`webhookArrivingBeforeTheSubmitResponseIsNotOverwritten`).
6. **Webhooks:**
   - HMAC-SHA256 over the raw body, compared in constant time;
   - deduplicated by `eventId` in the same transaction as the effect;
   - a conflicting final outcome is accepted (200, so the rail stops retrying) but never flips the state; it is logged for a human;
   - an unknown reference gets 404 and the dedupe row is rolled back, so a retry can succeed.
7. **Retries:** exponential backoff with equal jitter, capped.
8. **Circuit breaker** (hand-written, ~60 lines) around the rail. Only `Unknown` outcomes count as failures. While it is open, claimed payouts are released untouched (their attempts aren't counted).

## Alternatives
- **Resilience4j:** the production choice. The breaker is hand-written here to study the state machine.
- **Temporal workflow per payout:** durable timers and retries for free. Worth it when flows get long (M10 candidate). The DB-driven dispatcher shows what Temporal abstracts away.
- **Retrying inside the Kafka listener:** this would block the partition for minutes and couple payout latency to consumer lag. Instead the listener just records PENDING (M4) and the dispatcher owns retries.

## TODO
- Report SETTLED/REJECTED back to transfer-service (`payouts.events.v1` via an outbox) → transfer COMPLETED/FAILED.
- Per-rail breakers and bulkheads (one breaker per rail/corridor).
- Metrics: payouts by status, age of the oldest UNKNOWN, breaker state (M8).
