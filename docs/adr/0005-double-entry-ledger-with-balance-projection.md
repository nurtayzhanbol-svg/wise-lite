# ADR-0005: Double-entry ledger with a locked balance projection and database-level guards

- Status: accepted
- Date: 2026-10-03

## Context
Every movement of money must be traceable, atomic and impossible to "lose". Balances must be fast to read and safe to debit concurrently.

## Decision
1. **Journal entries and ledger entries.** A `JournalEntry` is a set of `Posting`s (account, signed amount) that must sum to zero **per currency**. Money only moves between accounts; it is never created or destroyed. Money entering or leaving the system goes through system accounts (`EXTERNAL_FUNDING`, `FX_POOL`, `FEE_REVENUE`).
2. **Append-only.** Ledger rows are never updated or deleted. Mistakes are fixed with a new, reversing entry.
3. **Balance projection.** `account_balances.balance_minor` holds `SUM(ledger_entries.amount_minor)` and is updated in the same transaction as the entries. Reads are O(1), and the row is what a debit locks.
4. **Locking.** `LedgerService.post` takes `SELECT … FOR UPDATE` row locks on every touched balance, in ascending account-id order (prevents deadlocks), checks for overdraft, then writes. Alternatives are compared in M3.
5. **Defence in depth in Postgres:**
   - a deferred constraint trigger rejects any unbalanced journal entry at `COMMIT`;
   - `BEFORE UPDATE OR DELETE` triggers make the ledger append-only;
   - `CHECK (allow_negative OR balance_minor >= 0)` on balances;
   - a composite foreign key `(account_id, currency) → accounts(id, currency)`, so an entry's currency always matches its account's.

## Alternatives
- **Single-entry (just a `balance` column updated in place):** no audit trail, and a bug silently creates or destroys money. Rejected.
- **No projection (always `SUM` the entries):** simplest and always consistent, but reads get slower as history grows, and there is no natural row to lock. Could be revisited with periodic balance snapshots.
- **Checks only in the application:** a bug, a manual SQL fix or a second service writing to the database could corrupt the books. The database guards cost little.

## Consequences
- Debits on the same account are serialised by its row lock. A very hot account (e.g. a system account touched by every top-up) becomes a bottleneck. M3 measures this and discusses mitigations: sharded system accounts, or not locking accounts that are allowed to go negative.
- The deferred trigger re-sums the journal once per inserted row, which is negligible for journals with a handful of postings.
