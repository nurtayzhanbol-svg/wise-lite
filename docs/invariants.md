# System invariants

Properties that must hold at all times. Each one will be backed by a test that fails if it is violated (link added when implemented).

| # | Invariant | Enforced by | Test |
|---|-----------|-------------|------|
| I1 | Every ledger transaction balances: the sum of its entries per currency is 0 | `JournalEntry` + deferred DB trigger | `JournalEntryTest`, `LedgerDatabaseGuardsIT` |
| I2 | A customer balance never goes below 0 | `LedgerService` overdraft check + DB `CHECK`; under concurrency: ordered `FOR UPDATE` | `LedgerServiceIT`, `LedgerDatabaseGuardsIT`, `LedgerConcurrencyIT` |
| I3 | Ledger entries are append-only (no updates or deletes) | DB triggers | `LedgerDatabaseGuardsIT` |
| I1b | Balance projection equals SUM(entries); total money is conserved | single DB transaction in `LedgerService.post` | `LedgerServiceIT.balanceProjectionAlwaysMatchesTheLedger` |
| I4 | The same Idempotency-Key never creates more than one transfer | key row in the business transaction + PK `(owner_id, key)` | `TransferApiIT` (incl. 16-thread race) |
| I5 | A transfer only moves through allowed state transitions; each money movement happens once | `TransferState` table, row lock, same-state no-op, DB `CHECK` on state | `TransferStateTest`, `TransferServiceIT` |
| I5b | `PAYOUT_CLEARING` balance = sum of FUNDED + PROCESSING transfers | state change and journal in one transaction | `TransferServiceIT` (checked globally in M7) |
| I6 | Every committed state change eventually produces exactly one event *effect* downstream | M4 | — |
| I7 | A transfer is paid out at most once, even with retries and duplicate callbacks | M6 | — |
| I8 | Every ledger payout matches a bank-side record, or appears in the reconciliation report | M7 | — |
