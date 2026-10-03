# ADR 0017: Risk gating and event ordering (HELD transfers)

- Status: accepted (M10)
- Related: ADR 0008 (state machine), 0010 (outbox), 0011 (idempotent consumers), 0013 (payouts), 0016 (risk engine)

## Context

Until M9, `FUNDED` was both "money reserved" and "go pay it": payout-worker created a payout for every
`toState=FUNDED` event. The risk engine read the same topic and only emitted alerts. Making risk a real gate
raises an ordering problem: two consumers read the same event independently and in no defined order. If the
payout path does not wait for risk, a payout can reach the external rail before the risk verdict arrives.

The rail is an external system: once a payment is submitted it may be settled, and we cannot reliably call it
back (M6: even a timeout is an *unknown* outcome). So "pay, and cancel if risk says BLOCK" is not an option.

## Decision

**1. The gate is a state, owned by transfer-service.** The state machine gets two states:

```
FUNDED   = money in clearing, waiting for a risk decision
HELD     = money in clearing, waiting for a human
APPROVED = risk said yes; the only state from which a payout may exist
```

Transitions: `FUNDED → APPROVED | HELD | FAILED`, `HELD → APPROVED | FAILED`, `APPROVED → PROCESSING | FAILED`.
`FUNDED → PROCESSING` was removed. There is no `APPROVED → HELD`: approval is never revoked.

**2. payout-worker acts only on `toState=APPROVED`.** A payout row (and therefore any rail submission) cannot
exist before approval. Nothing must be cancelled, because nothing unapproved was ever actionable.

**3. risk-engine emits one `RiskDecision` per FUNDED transfer** on `risk.decisions.v1`, key = transferId,
`decisionId = UUIDv3("risk-decision|" + transferId)`. Rules: hard amount limit and IBAN blocklist → BLOCK;
review amount, per-owner velocity, rolling 24 h volume → REVIEW; otherwise ALLOW. The stream is repartitioned
by owner so one task owns an owner's history (a state store). The M9 alert topology is unchanged.

**4. transfer-service applies a decision in one transaction:** `processed_events` dedupe row, `risk_decisions`
audit row, the state change (+ refund journal for BLOCK), and the outbox event. Decisions apply **only to a
transfer that is still FUNDED**, under its row lock. Every other case (duplicate with a new id, re-run of rules,
decision after timeout, after a manual verdict, after a terminal state) is recorded as `IGNORED`.
"First decision wins" needs no sequence numbers, because a decision is a one-shot question with exactly one
state in which it can be answered.

**5. Missing decision → fail closed, to HELD.** `RiskTimeoutService` holds FUNDED transfers older than
`wiselite.risk.decision-timeout` (default 5 min). Not fail-open (risk-engine down would mean no risk checks:
the outage an attacker wants). Not auto-refund (a slow engine would cancel legitimate transfers and customers
would retry, doubling load). A HELD transfer is safe: the money is reserved and nothing moves until a human
decides. The first sweep waits one full timeout after startup so the consumer can drain decisions that queued
up while transfer-service itself was down. A late automated decision is recorded but does not release.

**6. Manual verdicts** (`POST /internal/transfers/{id}/release|reject`, `X-Operator` header) are idempotent by
state under the row lock: repeating the applied verdict returns the transfer unchanged; the opposite verdict,
or any verdict on a non-HELD transfer, is 409. A partial unique index allows at most one applied decision per
`(transfer, source)` as a database-level backstop.

**7. Detective control:** reconciliation reports `PAID_BEFORE_APPROVAL` (critical) when the rail has a
payment for a transfer that is FUNDED or HELD in our snapshot.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Payout-worker consumes both topics and joins FUNDED with ALLOW | The gate then lives in a second database that can disagree with transfer-service; manual release and timeouts would need a third place. One source of truth is simpler. |
| Synchronous risk call inside `POST /transfers` | Couples transfer creation latency and availability to the risk engine, and the windowed rules need the stream anyway. Reasonable for a hard sanctions check at very small scale; noted in the interview doc. |
| Create the payout as PENDING and "cancel" it on BLOCK | Races the dispatcher; after submission the outcome is unknown and cancelation is not guaranteed by the rail. Rejected — this is exactly the bug we're designing out. |
| Fail open on timeout | Turns a risk-engine outage into an unchecked-payout window. |
| Fail to BLOCK/refund on timeout | Customer-hostile; a transient outage cancels good transfers. Kept as a possible per-segment policy. |
| Per-transfer version numbers on decisions | Not needed: the state machine already rejects any decision not addressed to FUNDED. Would be needed if decisions could be *revised* (e.g. ALLOW → later BLOCK while still unpaid). |
| Late automated decision releases a timed-out HELD | Convenient, but then HELD has two exits (operator, engine) that race. Kept strict; the audit row tells the operator what the engine said. |

## Consequences

- Payout latency now includes the risk round trip (outbox relay → Streams → consumer), typically < 1 s.
- Old FUNDED events in flight during a deploy no longer create payouts; such transfers time out to HELD.
- The timeout must exceed consumer recovery time (group session timeout after a crash), or crashes produce
  spurious holds (see `RiskGateSystemTest`, which uses 60 s).
- Kafka down means no decisions: transfers queue in FUNDED, then HELD after the timeout. Safe, but an outage
  longer than the timeout creates operator work. Alert on `wiselite_risk_decisions` rate and HELD count.
