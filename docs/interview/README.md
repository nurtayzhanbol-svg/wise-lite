# Interview notes

One file per topic, written so you can explain and defend the design in a Wise system design interview. Each file follows the same structure:

1. **The problem**: what goes wrong without this.
2. **What wise-lite does**: the chosen solution, with links to code and tests.
3. **Alternatives and why not**.
4. **Invariants and failure modes**.
5. **Likely follow-up questions**, with short answers.
6. **Rebuild exercise**: what to delete and rewrite yourself.

| Topic | Milestone | File |
|-------|-----------|------|
| Money representation & double-entry ledger | M1 | — |
| Idempotency keys | M2 | — |
| State machines for payments | M2 | — |
| Isolation levels, pessimistic vs optimistic locking | M3 | — |
| Transactional outbox vs 2PC vs CDC | M4 | — |
| Kafka delivery semantics & idempotent consumers | M4 | — |
| Caching, staleness, time in tests | M5 | — |
| Retries, backoff, circuit breakers, unknown outcomes | M6 | — |
| Reconciliation | M7 | — |
| Observability, SLOs | M8 | — |
| Stream processing windows | M9 | — |
| Scaling wise-lite 100× | final | — |
