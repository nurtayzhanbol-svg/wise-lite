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
| FX provider is down | Recent cached rate used within a freshness limit, otherwise a clear 503 | M5 | — |
| Payout request times out but the bank actually paid | Transfer stays in an "unknown" state until confirmed; never paid twice | M6 | — |
| Bank sends a duplicate or late webhook | Ignored or applied idempotently | M6 | — |
| Ledger and bank statement disagree | Mismatch appears in the reconciliation report | M7 | — |
| Same key reused for a different request | 422, nothing executed | M2 | `TransferApiIT.sameKeyWithDifferentBodyIsRejected` |
| Request fails (insufficient funds), client retries later with same key | Key not stored; retry runs fresh | M2 | `TransferApiIT.failedRequestIsNotStored…` |
| Duplicate `complete`/`fail` event | No-op; journal posted once | M2 | `TransferServiceIT.duplicateEventsAreNoOps` |
| `fail` arrives after `complete` | 409; no money moves | M2 | `TransferServiceIT.illegalTransitions…` |
