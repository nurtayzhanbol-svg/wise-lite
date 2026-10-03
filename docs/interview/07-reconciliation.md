# Reconciliation (M7)

Code: `services/reconciliation-job` — `Reconciler` (pure rules), `TransferSource` (snapshot + ledger checks), `PayoutSource`, `RailStatementClient`, `ReconciliationService`. Tests: `ReconcilerTest` (incl. a jqwik property), `ReconciliationIT` (real migrations of the other services).

## 1. Why it exists
Prevention mechanisms have bugs; people run manual SQL; banks make mistakes. Reconciliation is the **independent detective control**: compare what we think happened with what the counterparty says happened, and alert on every difference. Line to remember: *"idempotency prevents duplicates; reconciliation proves there weren't any."*

## 2. What is compared
| Source | Holds | Check |
|---|---|---|
| Ledger (transfers DB) | entries, balances, transfer states | trial balance = 0; projection = Σ entries; clearing = Σ in-flight transfers |
| payouts DB | payout status | funded but no payout → lost event |
| Rail statement | what the bank did | each transfer ↔ at most one payment; amount, currency and status agree |

## 3. Things to be able to explain
1. **Snapshot consistency inside one DB:** REPEATABLE READ = one MVCC snapshot for all queries, so a commit in the middle of the run can't produce a fake clearing mismatch. Under READ COMMITTED each query sees a different moment.
2. **No snapshot across systems** → read order (us first, the rail last) + grace window. Explain why the opposite order produces false `UNKNOWN_AT_RAIL`.
3. **Severity:** `PAID_BUT_REFUNDED` is the most expensive break (the customer and the recipient both got the money). Recovery is a manual recall from the bank.
4. **Defense in depth:** DB triggers already prevent unbalanced entries. Recon still checks, because triggers can be disabled. The test does exactly that.
5. **Read-only access + its own DB:** the job can't make things worse.
6. **Scale:** date-windowed runs, indexed `updated_at`, hashing per range, statement files instead of an API.

## 4. Likely questions
- "Recon found a SETTLED payment for a REFUNDED transfer. What now?" → Page someone, freeze the customer's balance if needed, recall the payment from the rail, then post a correcting journal entry (never edit the ledger).
- "How often should it run?" → Daily per the statement cycle, plus intraday runs for fast rails. Alert on the age of the oldest unresolved break.
- "Why not fix automatically?" → Only unambiguous cases (STUCK with a final rail outcome), and only through the normal idempotent paths.

## 5. Rebuild exercise
1. Swap the read order in `ReconciliationService.run` and write a test that shows the false positive.
2. Change `TransferSource` to READ COMMITTED and write a concurrency test that produces a spurious `CLEARING_MISMATCH`.
3. Implement the auto-resolver for `STUCK_RESOLVABLE` by sending a signed callback to payout-worker.
