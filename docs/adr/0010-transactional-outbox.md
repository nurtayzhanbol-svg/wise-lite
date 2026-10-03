# ADR 0010: Transactional outbox for publishing transfer events

## Status
Accepted (M4)

## Context
Every transfer state change must reach Kafka, because payout-worker, risk-engine and reconciliation depend on those events. Writing to Postgres and then to Kafka is a **dual write**:
- DB commits, then the process crashes before the Kafka send: the event is lost and the transfer stays FUNDED forever.
- Kafka send succeeds, then the DB transaction rolls back: a payout goes out for a transfer that doesn't exist.

## Decision
- `TransferService.advance` appends a `TransferStateChanged` row to `outbox_events` **in the same transaction** as the state change and ledger journal. `OutboxWriter.append` uses `Propagation.MANDATORY`, so calling it outside a transaction throws.
- `OutboxRelay` polls the unpublished rows (`ORDER BY seq ... FOR UPDATE SKIP LOCKED`), sends them with an idempotent producer (`acks=all`, `enable.idempotence=true`) and waits for the acks. Only then does it mark the rows `published_at`.
- Kafka key = transfer id, so every event of one transfer goes to one partition, in order.
- Delivery is **at-least-once**. A crash between the Kafka ack and the DB commit re-sends the batch. Consumers dedupe by `event-id`.

## Alternatives
- **CDC with Debezium** (tail the WAL). There is no polling and the latency is lower, but it adds Kafka Connect + Debezium to operate. That is the right call at Wise scale and overkill for a learning project. The outbox table design is the same either way, so switching later is cheap.
- **Kafka transactions (exactly-once)**: these only cover Kafka→Kafka. They can't make a Postgres commit and a Kafka write atomic.
- **Publish after commit (`@TransactionalEventListener(AFTER_COMMIT)`)**: still loses the event if the process crashes after the commit.
- **Listen-to-yourself** (write only to Kafka and consume your own event to update the DB): the API can't read its own writes synchronously.

## Consequences
- Publishing latency is about the polling interval (200 ms). This could be lowered with `LISTEN/NOTIFY`.
- Per-key ordering assumes a single active relay. With several relays using SKIP LOCKED, two batches containing events of the same transfer could be sent concurrently and out of order. Options: leader election, or partition outbox rows by key hash so that one relay owns each slice. TODO.
- The outbox table grows. Published rows need a retention job (e.g. delete rows older than 7 days). TODO.
- If Kafka is down, the business keeps working and the outbox backlog grows. Alert on the unpublished count and the age of the oldest row (M8).
