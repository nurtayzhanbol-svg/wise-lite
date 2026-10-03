# TODO for me (manual exercises after the sprint)

- [ ] M0: Set up the Gradle multi-module build from scratch in an empty repo without looking at this one.
- [ ] M1: Rebuild `LedgerService.post` and the V2 triggers from memory (see `docs/interview/01-ledger.md` §6).
- [ ] M1: Remove the lock ordering and write a test that reproduces a deadlock.
- [ ] M1: Add a scheduled "balance drift" check that compares `account_balances` with `SUM(ledger_entries)`.
- [ ] M2: Rebuild `IdempotencyService.execute` from ADR 0007 (see `docs/interview/02-idempotency-and-state-machines.md` §4).
- [ ] M2: Add a TTL cleanup job for `idempotency_keys`.
- [ ] M2: Variant: store 4xx responses using a savepoint (`Propagation.NESTED`) and compare the behaviour.
- [ ] M2: Add `CANCELLED` (customer cancels while FUNDED) with a refund journal and tests.
- [ ] M3: Implement "validate before lock" pessimistic variant and re-run the benchmark.
- [ ] M3: Add a retry-on-40P01/40001 wrapper (with jitter) as a safety net and test it.
- [ ] M3: Prototype sharded fee account (N sub-accounts) and benchmark hot-account throughput.
