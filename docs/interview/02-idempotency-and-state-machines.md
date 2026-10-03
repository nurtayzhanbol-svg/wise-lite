# Idempotency keys and payment state machines (M2)

Code: `idempotency/IdempotencyService`, `transfer/TransferState`, `transfer/TransferService`, tests `api/TransferApiIT`, `transfer/TransferServiceIT`, `transfer/TransferStateTest`.

## 1. The problem in one sentence
Networks fail *after* the server has done the work. The client can't tell "not done" apart from "done, but the response was lost", so it retries, and retries must not move money twice.

## 2. What you must be able to explain
1. **Why the key must be stored in the same transaction as the effect.** If the key commits but the transfer doesn't (or the other way round), you get either a lost transfer or a duplicate. This is the same atomicity argument as the outbox in M4.
2. **How two simultaneous requests with the same key behave.** Postgres unique index + `INSERT ... ON CONFLICT DO NOTHING`: the second inserter *waits* for the first transaction, then sees its committed row. Proven by `concurrentRequestsWithTheSameKeyCreateExactlyOneTransfer` (16 threads).
3. **Why you compare a request hash.** If a client reuses a key for a different payment, that is a bug. Silently replaying the old response would hide it, so the API returns 422.
4. **Why the hash covers the parsed command, not raw bytes.** `"30"` and `"30.00"` are the same amount, and JSON field order is irrelevant.
5. **Key scope.** Keys are per client. Global keys would let one customer's key collide with (or probe) another's.
6. **What is stored for failures, and why.** Here, nothing is stored: the failed attempt rolled back, so re-running it is safe. Know the opposite choice (store 4xx) and its trade-off (ADR 0007).
7. **Idempotency at every layer.** The API uses keys, the state machine treats "same state" as a no-op, the ledger journal references are deterministic (`transfer:<id>:payout`), and Kafka consumers (M4) dedupe by event id. Defence in depth.
8. **State machine benefits.** Illegal states can't be represented, the audit history is free, and duplicate/out-of-order events are safe: a late `fail` after `complete` → 409 and no money moves.
9. **Why FAILED → REFUNDED happens in one transaction.** There is never a moment where the money is in clearing for a failed transfer with no refund scheduled. Discuss when you would split them (a refund that needs an external call).

## 3. Likely interviewer questions
- "What happens if the server crashes halfway?" → The transaction rolls back, and the key row disappears with it. The retry runs fresh.
- "How long do you keep keys?" → The TTL must be longer than the client's maximum retry window (e.g. 24h). Cleanup job is a TODO.
- "What if the operation calls a slow external API?" → You can't hold the transaction. Use the IN_PROGRESS/recovery-point pattern (Stripe), or make the external call asynchronous via the outbox, which is the approach taken here.
- "Exactly-once?" → Not over a network. You get at-least-once delivery plus idempotent processing, which is effectively-once *effects*.
- "Row lock vs optimistic version?" → See M3 benchmark.

## 4. Rebuild exercise
Delete `IdempotencyService.execute`'s body and rewrite it from the ADR. Make `TransferApiIT` green again. Then break it on purpose: move the key insert into a `REQUIRES_NEW` transaction and watch which test fails, and why.
