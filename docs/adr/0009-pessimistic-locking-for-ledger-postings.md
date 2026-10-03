# ADR 0009: Pessimistic row locks, in global order, for ledger postings

## Status
Accepted (M3). Confirms the M1 choice with measurements.

## Context
Concurrent debits of one balance must not overdraw it or lose updates. Four strategies were compared in `lab/` (results: `docs/benchmarks/m3-locking.md`): `SELECT FOR UPDATE`, optimistic version CAS, SERIALIZABLE isolation, and a single conditional `UPDATE`.

## Decision
- `LedgerService.post` keeps `SELECT ... FOR UPDATE` on `account_balances`, acquiring locks in ascending account-id order.
- READ COMMITTED isolation is enough, because every value we decide on is read under a row lock.
- Every writer, whatever the strategy, must touch rows in the same global order. Optimistic writers take row locks too when they `UPDATE` (`ConcurrencyAnomaliesIT` and the benchmark showed deadlocks until the writes were sorted).

## Consequences
- No retry logic is needed in callers. Latency is predictable when contention is low; when contention is high, requests queue on the row instead of failing.
- Hot accounts serialise. Mitigations (not implemented): keep the critical section minimal (validate before locking), shard system accounts into N sub-accounts, or post to system accounts asynchronously in batches.
- If a deadlock ever happens anyway (a bug in ordering), Postgres aborts one transaction with 40P01 after `deadlock_timeout` (1 s). A retry-on-40P01 wrapper would be a safety net. TODO.

## Rejected
- **Optimistic CAS:** wasted work and starvation on hot rows (transfers that gave up in the benchmark).
- **SERIALIZABLE:** correct without thinking about locks, but every caller needs a retry loop, there are false-positive aborts, and throughput is the worst on the spread workload. A reasonable choice for complex read-mostly invariants, not for this hot path.
- **Conditional `UPDATE` only:** the fastest, but it can't validate currency or write journal + projection with read-dependent logic. It is still a great pattern for simple counters and stock reservations.
