# TODO for me (manual exercises after the sprint)

- [ ] M0: Set up the Gradle multi-module build from scratch in an empty repo without looking at this one.
- [ ] M1: Rebuild `LedgerService.post` and the V2 triggers from memory (see `docs/interview/01-ledger.md` §6).
- [ ] M1: Remove the lock ordering and write a test that reproduces a deadlock.
- [ ] M1: Add a scheduled "balance drift" check that compares `account_balances` with `SUM(ledger_entries)`.
