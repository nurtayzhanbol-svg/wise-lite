# ADR 0011: Idempotent consumers, bounded retries, dead-letter topic

## Status
Accepted (M4)

## Decision
- **Offsets are committed after processing** (`enable-auto-commit: false`, `ack-mode: record`), which makes delivery at-least-once.
- **Dedupe in the consumer's own DB, in the same transaction as the effect.** `processed_events(event_id PK)` plus the effect (`payouts`) commit together. A redelivery hits the PK and becomes a no-op.
- **Second line of defence: a natural key.** `payouts.transfer_id` is the PK, so even two *different* events for the same transfer pay out once.
- **Errors:** `DefaultErrorHandler` retries transient failures in-process with exponential backoff (about 2 s total), then publishes the record to `transfers.events.v1.DLT`. `MalformedEventException` skips the retries. The partition keeps moving, so one poison message can't block every transfer behind it.

## Why not Kafka exactly-once semantics?
EOS (transactional producer + `read_committed`) gives exactly-once only for read-from-Kafka/write-to-Kafka pipelines. Our effect is a Postgres write (and in M6, an HTTP call to a bank). For those, "exactly-once" has to be built as **at-least-once delivery + idempotent effect**.

## Consequences
- `processed_events` grows. Keep it for longer than the maximum redelivery window (topic retention), then prune.
- Records on the DLT need an operator workflow (inspect, fix, replay). TODO: a replay tool.
- Retrying in-process blocks the partition during the backoff. For long backoffs, use retry topics (`@RetryableTopic`), at the cost of losing ordering.
