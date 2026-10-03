# Concurrency control for money (M3)

Code: `lab/LockingStrategy`, `lab/ConcurrencyAnomaliesIT`, `lab/LockingBenchmarkIT`, `ledger/LedgerConcurrencyIT`, `ledger/LedgerService`. Results: `docs/benchmarks/m3-locking.md`.

## 1. Anomalies you must be able to name and reproduce
| Anomaly | What happens | Reproduced in |
|---------|--------------|---------------|
| Lost update | T1 and T2 read 1000 and both write 999; one debit vanishes | `naiveReadModifyWriteLosesUpdatesAndCreatesMoney` |
| Check-then-act race (write skew on one row) | Both see balance 10 ≥ 10 and both spend it, so the account is overdrawn | `naiveCheckThenActOverdraws` |
| Deadlock | T1 locks A then wants B; T2 locks B then wants A | `lockingInRequestOrderDeadlocksAndPostgresAbortsOneTransaction` (SQLSTATE 40P01) |

## 2. Why READ COMMITTED alone doesn't save you
Each statement sees committed data, but nothing stops another transaction committing *between* your SELECT and your UPDATE. You need one of: a row lock (`FOR UPDATE`), a conditional write (`WHERE version = ?` / `WHERE balance >= ?`), or SERIALIZABLE.

## 3. The four correct strategies, one line each
- **Pessimistic:** lock first, then read, decide and write. Others wait. No retries.
- **Optimistic:** read a version, write `WHERE version = ?`. 0 rows updated → retry. Great with low contention, bad on hot rows.
- **SERIALIZABLE (SSI in Postgres):** the DB detects dangerous read/write dependencies and aborts one transaction with 40001. You must retry, and false positives happen.
- **Atomic conditional UPDATE:** `UPDATE ... SET balance = balance - x WHERE id = ? AND balance >= x`. Postgres re-checks the WHERE clause on the newest row version after taking the lock (EvalPlanQual). This is the fastest option, but it only works when the decision fits in one statement.

## 4. Deadlocks
- They need a cycle in the waits-for graph. Prevent them by always acquiring locks in the same global order (here: sort by account UUID).
- **This applies to optimistic writers too:** `UPDATE` takes row locks until commit. The first benchmark run deadlocked until the writes were sorted.
- Postgres detects a deadlock after `deadlock_timeout` (default 1 s) and aborts one victim. Detection is a safety net, not a design, because every deadlock costs a second of latency.

## 5. Hot accounts: the real scaling question
"What if one account (a fee account, a large merchant) receives thousands of postings per second?"
1. Shrink the critical section: do validation before locking, and hold the lock only for the write.
2. Shard the account into N sub-accounts (`fee_revenue_0..15`), pick one at random, and sum them for reads.
3. Make system-side postings asynchronous: append the entry, then aggregate the balance in batches. Customer accounts still need synchronous checks, system accounts may not.
4. Partition the ledger by account so that unrelated accounts never contend.

## 6. Likely questions
- "Optimistic or pessimistic for a payments ledger?" → Measure it. The data here: pessimistic for predictable p99 with zero retries on the hot path, optimistic where conflicts are rare.
- "Why not just SERIALIZABLE everywhere?" → Retries leak into every caller, false positives occur, and throughput drops (the spread workload is about 40% slower here).
- "How do you test concurrency?" → Real Postgres (not H2), many threads released by a latch, then assert the *invariants* (conservation, no negatives, projection = sum) rather than specific interleavings.
- "Can a deadlock still happen in your ledger?" → Only if some code path locks in a different order. The fixed order is enforced in one place (`LedgerService.post`).

## 7. Rebuild exercise
In `LedgerService.post`, remove `.sorted(...)` and run `LedgerConcurrencyIT.opposingTransfersDoNotDeadlockAndConserveMoney`. Watch it fail with 40P01. Restore it. Then implement the "validate before lock" pessimistic variant in `LockingStrategy` and re-run the benchmark.
