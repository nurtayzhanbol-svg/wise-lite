# M3: Locking strategy benchmark results

Source: `services/transfer-service/src/test/java/com/wiselite/transfer/lab/LockingBenchmarkIT.java`.
Re-run with `./gradlew :services:transfer-service:benchmark`; the report is written to `build/reports/benchmark/locking.md`.
Machine: Devin VM, 8 vCPU, Postgres 16 in Docker (Testcontainers), 2026-10-03. Absolute numbers will differ on your laptop; compare the **shape**.

32 threads, 3000 transfers per run, 1 ms simulated work inside each transaction, 8 CPUs.

| Workload | Strategy | Throughput (ops/s) | p50 (ms) | p99 (ms) | Retries | Gave up | Money conserved |
|----------|----------|-------------------:|---------:|---------:|--------:|--------:|:---------------:|
| hot | PESSIMISTIC | 609 | 32.1 | 454.2 | 0 | 0 | yes |
| hot | OPTIMISTIC | 653 | 1.5 | 672.8 | 10503 | 15 | yes |
| hot | SERIALIZABLE | 604 | 1.7 | 534.1 | 12575 | 4 | yes |
| hot | ATOMIC_UPDATE | 4409 | 5.1 | 30.1 | 0 | 0 | yes |
| spread | PESSIMISTIC | 10921 | 2.6 | 5.7 | 0 | 0 | yes |
| spread | OPTIMISTIC | 11084 | 2.4 | 9.4 | 280 | 0 | yes |
| spread | SERIALIZABLE | 6647 | 2.6 | 37.4 | 1328 | 0 | yes |
| spread | ATOMIC_UPDATE | 18937 | 1.6 | 2.8 | 0 | 0 | yes |

## How to read it

- **Hot account** (every transfer debits account 1). Strategies that hold the row lock while they "think" serialise on that row. With 1 ms of work per transaction, the ceiling is roughly 1000 ops/s:
  - PESSIMISTIC queues: p50 is high, but there are no retries.
  - OPTIMISTIC and SERIALIZABLE look fast at p50 (the winners), but they burn thousands of retries. Their p99 is the worst, and a few transfers **gave up** after 50 retries. Under contention, optimistic concurrency turns into wasted work plus starvation.
  - ATOMIC_UPDATE is about 7× faster. That is not magic: it holds the row lock only for the single `UPDATE ... WHERE balance >= ?`, and the 1 ms of work happens before the lock is taken. **The lesson is to shrink the critical section.** A pessimistic variant that did its work before `FOR UPDATE` would close much of the gap.
- **Spread** (random pairs among 1000 accounts). Conflicts are rare, so every strategy works. SERIALIZABLE is still the slowest because of SSI predicate-lock bookkeeping and false-positive serialization failures (1328 retries without any real conflict on most rows).

## Why production `LedgerService` still uses PESSIMISTIC

It needs to *read* each balance, validate currency and overdraft, write journal rows **and** update projections in one transaction. A single conditional `UPDATE` can't express all of that. Pessimistic locking gives predictable latency with zero retries, and in a payments system "slow but certain" beats "fast at p50, starves at p99". For genuinely hot accounts (system/fee/clearing accounts), the scalable answer is structural rather than a cleverer lock. See `docs/interview/03-concurrency.md` §5.
