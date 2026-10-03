# Transactional outbox, Kafka delivery semantics, idempotent consumers (M4)

Code: `transfer-service/.../outbox/*`, `TransferService.advance`, `payout-worker/.../PayoutService`, `KafkaConsumerConfig`. Tests: `OutboxIT`, `PayoutWorkerIT`.

## 1. The dual-write problem (be able to draw it)
```mermaid
sequenceDiagram
    participant API as transfer-service
    participant DB as Postgres
    participant K as Kafka
    participant W as payout-worker
    API->>DB: BEGIN; update transfer; insert ledger rows; insert outbox row; COMMIT
    loop every 200ms
        API->>DB: SELECT unpublished FOR UPDATE SKIP LOCKED
        API->>K: send (acks=all, idempotent producer)
        K-->>API: ack
        API->>DB: mark published; COMMIT
    end
    K->>W: deliver (at-least-once)
    W->>W: BEGIN; insert processed_events(event_id); insert payout; COMMIT
    W->>K: commit offset
```
Where can it crash, and what happens?
| Crash point | Result |
|-------------|--------|
| Before the business commit | Nothing happened anywhere. Consistent. |
| After the commit, before the relay sends | The row waits in the outbox and is sent on the next tick. |
| After the Kafka ack, before `published_at` commits | Sent **again**. The consumer dedupes it. |
| Consumer: after the DB commit, before the offset commit | Redelivered. `processed_events` PK → skipped. |
| Consumer: before the DB commit | Redelivered and processed normally. |

## 2. Delivery semantics vocabulary
- **At-most-once:** commit the offset before processing. Messages can be lost.
- **At-least-once:** commit the offset after processing. Duplicates are possible. **This system uses it.**
- **Exactly-once:** impossible end-to-end over a network with side effects. Kafka EOS covers Kafka→Kafka only. Say "**effectively-once effects** = at-least-once + idempotent processing".

## 3. Ordering
- Kafka orders records only within a partition. Key = transferId → per-transfer order.
- The idempotent producer (`enable.idempotence`, `max.in.flight ≤ 5`) keeps that order even when the producer retries.
- Order breaks with multiple outbox relays (SKIP LOCKED), and with retry topics. Consumers should tolerate it anyway: the transfer state machine rejects impossible transitions and ignores repeats.

## 4. Poison messages
One bad record at offset N blocks its whole partition if you retry it forever. The rule: bounded retries → DLT → alert → manual replay. Proven by `poisonRecordGoesToTheDltAndDoesNotBlockThePartition`.

## 5. Likely questions
- "Why not just send to Kafka in the same method after saving?" → See §1, row 2 of the dual-write problem.
- "Outbox vs Debezium?" → Same table either way. CDC removes the polling but adds infrastructure (ADR 0010).
- "How would you know the relay is stuck?" → Unpublished count and oldest unpublished age as metrics, alert on age (M8).
- "What if the consumer's DB and the dedupe store are different systems?" → Then the dedupe isn't atomic with the effect, so you need an idempotent effect at the destination (an idempotency key on the bank API, M6).
- "How long do you keep processed_events?" → Longer than topic retention plus the replay window.
- "How do you replay from the DLT?" → A tool that re-publishes to the main topic after the fix. Dedupe makes replays safe.

## 6. Rebuild exercise
1. Remove the outbox: send to Kafka directly inside `TransferService.advance`. Write a test where the transaction rolls back after the send, and show the phantom event.
2. Remove the `processed_events` insert and the `ON CONFLICT` on `payouts`. Watch `redeliveredEventIsAppliedOnce` fail.
3. Switch the consumer to `enable-auto-commit: true` and explain which crash now loses a payout.
