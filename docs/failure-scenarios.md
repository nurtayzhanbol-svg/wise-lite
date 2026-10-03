# Failure scenarios

What can go wrong, and how the system is expected to behave. Filled in per milestone.

| Scenario | Expected behaviour | Milestone | Test |
|----------|-------------------|-----------|------|
| Client retries `POST /transfers` after a network timeout | Same transfer returned, no duplicate | M2 | — |
| Two concurrent debits from the same balance | One succeeds, the other is rejected or waits; balance never negative | M3 | — |
| Service crashes after DB commit, before publishing to Kafka | Event is still published (outbox) | M4 | — |
| Kafka delivers the same event twice | Consumer applies it once | M4 | — |
| FX provider is down | Recent cached rate used within a freshness limit, otherwise a clear 503 | M5 | — |
| Payout request times out but the bank actually paid | Transfer stays in an "unknown" state until confirmed; never paid twice | M6 | — |
| Bank sends a duplicate or late webhook | Ignored or applied idempotently | M6 | — |
| Ledger and bank statement disagree | Mismatch appears in the reconciliation report | M7 | — |
