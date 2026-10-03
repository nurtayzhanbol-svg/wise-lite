# Failure scenarios

What can go wrong, and how the system is expected to behave. Filled in per milestone.

| Scenario | Expected behaviour | Milestone | Test |
|----------|-------------------|-----------|------|
| Client retries `POST /transfers` after a network timeout | Same transfer returned, no duplicate | M2 | `TransferApiIT.retryWithSameKey…`, `concurrentRequests…` |
| Two concurrent debits from the same balance | One succeeds, the other is rejected or waits; balance never negative | M3 | `LedgerConcurrencyIT.concurrentDebitsNeverOverdraw` |
| Opposing transfers A→B and B→A at the same time | No deadlock (global lock order) | M3 | `LedgerConcurrencyIT.opposingTransfers…`; deadlock reproduced without ordering in `ConcurrencyAnomaliesIT` |
| Service crashes after DB commit, before publishing to Kafka | Event is still published (outbox) | M4 | `OutboxIT.stateChangeAndEventCommitTogether` |
| Kafka unavailable | Transfers still succeed; events queue in the outbox and publish after recovery | M4 | `OutboxIT.kafkaOutageKeepsEventsInTheOutboxUntilItRecovers` (pauses the broker container) |
| Kafka delivers the same event twice | Consumer applies it once | M4 | `PayoutWorkerIT.redeliveredEventIsAppliedOnce` |
| Malformed (poison) record | Sent to DLT; partition keeps flowing | M4 | `PayoutWorkerIT.poisonRecordGoesToTheDlt…` |
| Consumer DB blip | Retried with backoff; effect once | M4 | `PayoutWorkerIT.transientFailuresAreRetried…` |
| FX provider is down | Recent cached rate used within a freshness limit, otherwise a clear 503 | M5 | `QuoteApiIT.providerOutageDegradesThenFailsClosed`, `RateServiceTest` |
| Quote used twice / by two transfers concurrently | Exactly one wins; same consumer retry is idempotent | M5 | `QuoteApiIT.concurrentConsumersExactlyOneWins`, `quoteIsSingleUse…` |
| Quote used after TTL | 410 Gone | M5 | `QuoteApiIT.quoteExpiresAfterTtl` |
| Malicious/garbage rate XML | Rejected, last good snapshot kept | M5 | `EcbRateProviderTest` |
| Production-only config error (scheduler interval format) | Caught by booting with the relay enabled | M4/M5 | `OutboxRelaySchedulerIT` |
| Payout request times out but the bank actually paid | Transfer stays in an "unknown" state until confirmed; never paid twice | M6 | — |
| Bank sends a duplicate or late webhook | Ignored or applied idempotently | M6 | — |
| Ledger and bank statement disagree | Mismatch appears in the reconciliation report | M7 | — |
| Same key reused for a different request | 422, nothing executed | M2 | `TransferApiIT.sameKeyWithDifferentBodyIsRejected` |
| Request fails (insufficient funds), client retries later with same key | Key not stored; retry runs fresh | M2 | `TransferApiIT.failedRequestIsNotStored…` |
| Duplicate `complete`/`fail` event | No-op; journal posted once | M2 | `TransferServiceIT.duplicateEventsAreNoOps` |
| `fail` arrives after `complete` | 409; no money moves | M2 | `TransferServiceIT.illegalTransitions…` |
| Rail times out / 5xx after processing (unknown outcome) | Retried with the same Idempotency-Key; paid once | M6 | `PayoutDispatchIT.unknownOutcomeIsResolved…`, `RailsSimulatorIT.failureAfterProcessing…` |
| Rail keeps failing | Backoff + jitter; circuit opens; after max attempts MANUAL_REVIEW (never auto-failed) | M6 | `PayoutDispatchIT.circuitBreaker…`, `retriesStopAtMaxAttempts…` |
| Webhook arrives before submit response | Final status is not overwritten | M6 | `PayoutDispatchIT.webhookArrivingBefore…` |
| Duplicate / conflicting / forged webhook | Deduped / logged and ignored / 401 | M6 | `PayoutDispatchIT` |
| Rail rejects (bad IBAN) | REJECTED, no retries | M6 | `PayoutDispatchIT.badRequestIsAPermanentRejection` |
| Worker crashes mid-call | Lease expires, payout re-claimed, idempotent re-submit | M6 | design (ADR 0013) |
| Any prevention mechanism has a bug / manual SQL / bank error | Detected by nightly reconciliation, reported as a typed break with severity | M7 | `ReconciliationIT`, `ReconcilerTest` |
| Webhook lost forever, payout stuck in MANUAL_REVIEW | `STUCK_RESOLVABLE` break carries the rail's final outcome | M7 | `ReconciliationIT.stuckTransfer…` |
| First transfers on a fresh DB race to lazily create a system account | Was: the loser's unique violation aborted its transaction → 500 (found by the M8 system test). Now `INSERT … ON CONFLICT DO NOTHING` + re-select | M8 | `SystemAccountRaceIT` |
| Payout-worker SIGKILLed mid-batch, Kafka paused, rail faults, duplicate client retries — all at once | All transfers final, money conserved, ≤1 rail payment per transfer, recon clean | M8 | `ChaosSystemTest` |
