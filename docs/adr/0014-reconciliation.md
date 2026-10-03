# ADR 0014: Reconciliation job

## Status
Accepted (M7)

## Context
Every earlier mechanism (idempotency, outbox, dedupe, leases, state guards) tries to *prevent* inconsistency. Reconciliation is the independent control that *detects* it, including cases caused by bugs in those very mechanisms, by manual DB fixes, or by the third party. Finance teams treat it as mandatory. It is also how unknown outcomes left in MANUAL_REVIEW finally get resolved.

## Decisions
1. **A separate service with its own DB** (`recon`) for runs and breaks. It reads `transfers` and `payouts` through **read-only** connections (Hikari `readOnly`), plus the rail statement (`GET /statement`).
   - *Trade-off:* reading another service's database breaks service ownership. This is acceptable for a read-only control job; in production it would read replicas, a warehouse/CDC copy, or export files, never the primary.
2. **Ledger self-checks run in one REPEATABLE READ transaction**, so all queries see one MVCC snapshot:
   - trial balance per currency = 0;
   - the `account_balances` projection equals the sum of entries;
   - the `PAYOUT_CLEARING` balance equals the total of FUNDED and PROCESSING transfers.
3. **Cross-system checks can't share a snapshot.** The fix has two parts:
   - (a) Read our books first and the rail last. A rail payment is always created after its transfer, so the rail can only be *ahead* of us.
   - (b) A **grace window**: in-flight rules and "unknown at rail" only judge records older than `cutoff - grace`. The next run judges the younger ones.
4. **Break taxonomy with severity** (`BreakType`). CRITICAL = money is wrong: `PAID_BUT_REFUNDED`, `UNKNOWN_AT_RAIL`, `DUPLICATE_AT_RAIL`, `AMOUNT_MISMATCH`, and the ledger checks. HIGH = our status is wrong. MEDIUM = stuck but resolvable.
5. **Detect only, never auto-fix (for now).** The pure `Reconciler` makes the rules unit- and property-testable. An auto-resolver for `STUCK_RESOLVABLE` (feed the rail outcome back through the normal webhook/event path) is the obvious next step. It must go through the same idempotent paths and never write to other services' tables.

## Alternatives
- **Streaming reconciliation** (join Kafka topics continuously): lower latency, but harder to make complete. Batch over full state is the standard control and easier to audit.
- **Checksums or Merkle trees** over ranges: needed at scale to avoid full scans; out of scope.

## TODO
- Window by date (`created_at` ranges) instead of full scans.
- Auto-resolution of `STUCK_RESOLVABLE` via the payout-worker callback path.
- Metrics and alerting on CRITICAL breaks (M8).
