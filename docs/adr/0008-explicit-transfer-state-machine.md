# ADR 0008: Explicit transfer state machine, money moves with state

## Status
Accepted (M2)

## Decision
- `TransferState` lists the allowed transitions in one table: CREATED → FUNDED → PROCESSING → COMPLETED, with FUNDED/PROCESSING → FAILED → REFUNDED.
- Each transition and its ledger journal (fund / payout / refund) are committed in **one transaction**. The transfer's state and the money can't disagree.
- Concurrent transitions of one transfer are serialised with `SELECT ... FOR UPDATE` on the transfer row.
- A transition to the current state is a **no-op**. This makes duplicate events (webhooks, retries) harmless. Illegal transitions return 409.
- Every change is appended to `transfer_state_history` along with the reason.
- In-flight money sits in a `PAYOUT_CLEARING` system account. Its balance should always equal the sum of FUNDED + PROCESSING transfers, which M7 reconciliation can check.

## Alternatives
- **Optimistic CAS (`UPDATE ... WHERE state = ?`)** instead of a row lock. Fewer blocked threads, but the caller must retry. M3 compares the two.
- **Spring Statemachine / Temporal.** Too heavy for six states. Temporal is a stretch goal for long-running orchestration.
