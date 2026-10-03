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
| I5b | `PAYOUT_CLEARING` balance = sum of FUNDED + HELD + APPROVED + PROCESSING transfers | state change and journal in one transaction | `TransferServiceIT` (checked globally in M7) |
| I6 | Every committed state change eventually produces exactly one event *effect* downstream | outbox row in the same transaction; at-least-once relay; consumer dedupe (`processed_events`) + natural key (`payouts.transfer_id`) | `OutboxIT`, `PayoutWorkerIT` |
| I7 | A transfer is paid out at most once, even with retries and duplicate callbacks | M6 | — |
| I8 | Every ledger payout matches a bank-side record, or appears in the reconciliation report | M7 | — |
| I9 | A quote is consumed at most once; never after expiry | conditional UPDATE in `QuoteService.consume` + DB `CHECK`s | `QuoteApiIT` |
| I10 | Converted amount ≤ exact conversion, within 1 minor unit | `FxMath` rounding rules | `FxMathTest` (jqwik) |
| I11 | A payout is submitted to the rail under exactly one idempotency key (transfer id) | `RailsClient` | `PayoutDispatchIT.unknownOutcome…` |
| I12 | A final payout status (SETTLED/REJECTED) never changes | status-guarded UPDATEs in `PayoutStore` | `PayoutDispatchIT` conflict + race tests |
| I13 | PAYOUT_CLEARING balance = Σ amount of FUNDED/HELD/APPROVED/PROCESSING transfers, per currency | design of the transfer postings | `ReconciliationIT` (CLEARING_MISMATCH) |
| I14 | Each transfer has at most one rail payment, with equal amount and currency | rail idempotency key | `ReconcilerTest` |
| I15 | No payout exists (and nothing reaches the rail) for a transfer that was never APPROVED | payout-worker acts on `toState=APPROVED` only; `PROCESSING` reachable only from `APPROVED`; no `APPROVED → HELD` | `PayoutWorkerIT.unapprovedTransfersNeverGetAPayout`, `TransferStateTest.processingIsReachableOnlyFromApproved…`, `RiskGateSystemTest` (rail statement), recon `PAID_BEFORE_APPROVAL` |
| I16 | HELD means no payout: only an operator verdict leaves HELD | state machine + decisions apply only to FUNDED | `RiskGateIT.missingDecisionHolds…`, `conflictingLateDecisions…`, `RiskGateSystemTest.riskEngineDownFailsClosed` |
| I17 | A risk decision has at most one business effect, however often it is delivered | `processed_events` + FUNDED check + `risk_decisions` partial unique index, all in the effect's transaction | `RiskGateIT.redelivered…`, `crashMidDecision…`, `RiskGateSystemTest.replayedAndLateDecisionsChangeNothing` |
| I18 | Late/conflicting decisions never change a transfer that is not FUNDED (no resurrection of terminal transfers) | row lock + state check in `RiskDecisionHandler` | `RiskGateIT.conflictingLateDecisions…`, `decisionAfterCompletion…` |
| I19 | Releasing a HELD transfer N times → one APPROVED transition → ≤ 1 payout; rejecting N times → one refund; release vs reject → exactly one wins | row lock + state-based idempotency in `RiskOperatorService` | `RiskGateIT.release…/reject…/releaseRacingReject…`, `RiskGateSystemTest` |
| I20 | No decision within the timeout → HELD, never auto-approved | `RiskTimeoutService` (fail closed) | `RiskGateIT.missingDecisionHolds…`, `timeoutRacingTheDecision…` |
