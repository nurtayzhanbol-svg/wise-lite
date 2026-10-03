# Money representation & double-entry ledger

## 1. The problem
A payments company holds customers' money. Without discipline you get:
- **Rounding drift** from floating point.
- **Money created or destroyed by bugs.** For example, the balance update succeeds but the counterpart update fails.
- **No audit trail.** Nobody can answer "why is this balance 70?".
- **Overdrafts under concurrency** (covered in M3).

## 2. What wise-lite does
- `Money` = `long` minor units + `Currency` ([code](../../services/transfer-service/src/main/java/com/wiselite/transfer/ledger/Money.java), [ADR-0004](../adr/0004-money-as-long-minor-units.md)).
- `JournalEntry` = postings that sum to zero per currency ([code](../../services/transfer-service/src/main/java/com/wiselite/transfer/ledger/JournalEntry.java)).
- `LedgerService.post` locks balances in a fixed order, checks for overdraft, then inserts entries and updates balances in **one DB transaction** ([code](../../services/transfer-service/src/main/java/com/wiselite/transfer/ledger/LedgerService.java)).
- Postgres enforces the same rules again ([V2__ledger.sql](../../services/transfer-service/src/main/resources/db/migration/V2__ledger.sql), [ADR-0005](../adr/0005-double-entry-ledger-with-balance-projection.md)).

Worked example: Alice tops up €100, then converts €100 to $108.

| Journal | Account | Amount |
|---------|---------|--------|
| TOP_UP | EXTERNAL_FUNDING EUR | −100.00 |
| | Alice EUR | +100.00 |
| FX_CONVERSION | Alice EUR | −100.00 |
| | FX_POOL EUR | +100.00 |
| | FX_POOL USD | −108.00 |
| | Alice USD | +108.00 |

Sum per currency is zero for every journal. The system as a whole always sums to zero: the negative `EXTERNAL_FUNDING` balance mirrors "money our bank accounts hold for customers".

## 3. Alternatives and why not
- `double` gives wrong cents. `BigDecimal` is fine but easy to misuse (`equals` and scale); it will be used for rates in M5.
- Single-entry balance column: no audit trail, silent corruption.
- Summing entries on every read: correct, but O(history), and there is no row to lock.

## 4. Invariants and how they are tested
| Invariant | Test |
|-----------|------|
| Journal sums to 0 per currency | `JournalEntryTest` (property-based, jqwik); `LedgerDatabaseGuardsIT.unbalancedJournalEntryFailsAtCommit` |
| Ledger is append-only | `LedgerDatabaseGuardsIT.ledgerEntriesCannotBeUpdatedOrDeleted` |
| Customer balance ≥ 0 | `LedgerServiceIT.insufficientFundsRejectsTheWholeEntryAndPersistsNothing`; DB `CHECK` test |
| Projection = SUM(entries), total money conserved | `LedgerServiceIT.balanceProjectionAlwaysMatchesTheLedger` |
| Entry currency = account currency | `LedgerServiceIT.postingInTheWrongCurrencyIsRejected`; FK test |

## 5. Likely follow-up questions
- **Why minor units in a `long`?** Exact integer arithmetic, fast, maps to `BIGINT`, and overflow is detected with `addExact`.
- **How do you handle JPY or BHD?** Decimals come from `Currency.getDefaultFractionDigits()` (0 and 3).
- **How do you fix a wrong entry?** Post a reversing entry; history is never rewritten. Auditors and regulators require this.
- **Why validate in both Java and SQL?** Java gives clear errors; SQL protects against bugs, other writers and manual fixes. The cost is small.
- **What if the balance projection drifts from the entries?** It cannot inside a single transaction. A periodic job can re-sum and alert, as a cheap safety net (related to reconciliation, M7).
- **Hot accounts?** The system `EXTERNAL_FUNDING` account is locked by every top-up, which serialises them. Options: don't lock accounts that are allowed to go negative, shard system accounts (N sub-accounts), or batch postings. Measured in M3.
- **How would you scale the ledger?** Partition by account; a journal that spans partitions then needs a distributed transaction or a saga. That is why many ledgers keep a journal's postings on one shard (e.g. through a clearing account per shard).
- **Why not an event-sourced ledger?** The ledger *is* an append-only event log, and `account_balances` is its projection. This is the same idea with a relational store.

## 6. Rebuild exercise
Delete `LedgerService.post` and `V2__ledger.sql`'s triggers, and rewrite them from memory until `LedgerServiceIT` and `LedgerDatabaseGuardsIT` pass. Then remove the sort before locking and write a test that deadlocks (hint: two threads, A→B and B→A, in a loop).
