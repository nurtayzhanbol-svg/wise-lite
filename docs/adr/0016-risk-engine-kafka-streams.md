# ADR 0016: Risk engine on Kafka Streams

## Status
Accepted (M9)

## Context
Fraud and AML signals depend on *patterns over time*:
- many transfers in a short burst;
- large daily volume;
- many unrelated senders paying one recipient (a money-mule pattern).

These need stateful, windowed aggregation over the event stream we already publish (`transfers.events.v1`). Our principle is "every component demonstrates a trade-off". This one demonstrates event time vs processing time, windowing and late data, partition-local state, and the limits of "exactly-once".

## Decisions
1. **Kafka Streams** (a library, not a cluster). State lives in local RocksDB stores, backed by changelog topics. It scales by partitions and recovers by replaying the changelog. *Alternatives:* Flink (more powerful, separate cluster, overkill here); SQL over the transfers DB (simpler, but polls and puts load on the OLTP database).
2. **Input: FUNDED transitions only.** Money has left the customer's balance at that point.
3. **Event time from `occurredAt`** (`OccurredAtTimestampExtractor`). After a Kafka outage the outbox relay publishes the whole backlog at once. With processing time, that burst would look like a velocity attack.
4. **Dedupe by `eventId` before aggregating** (`FirstSeen` + window store, retention 24 h). `exactly_once_v2` makes *read→process→write inside Kafka* atomic. It does **not** know that two records with different offsets are the same business event, and the outbox can produce exactly that. Dedupe is partition-local, which is correct because duplicates share the key (`transferId`), hence the partition.
5. **Rules:**
   - `VELOCITY`: sliding 10 min, more than 5 transfers;
   - `DAILY_VOLUME`: tumbling 1 day, per owner and currency, sum above 10,000.00;
   - `MULE_RECIPIENT`: sliding 1 h, more than 3 distinct owners paying one IBAN.

   Each rule re-keys the stream (owner / owner+currency / IBAN), which creates a repartition topic. That is the cost of grouping by something other than the input key.
6. **Late data:** a 5-minute grace period. Later events are dropped from the windows, and this is visible in Kafka Streams' dropped-records metric.
7. **One alert per burst.** Sliding windows emit an update per record, so a second `FirstSeen` suppresses repeats per (rule, subject) within the window. `alertId` = UUIDv3(rule, subject, window start), so a replay produces the same ids and consumers can dedupe.
8. **Poison pills:** the deserializer returns null and the record is skipped. Uncaught exceptions replace the stream thread. Production would also route the bytes to a DLT.
9. **Detection, not prevention.** Alerts are async and post-funding. Blocking a transfer *before* funding needs a synchronous check in transfer-service (latency on the hot path, and what to do when the risk service is down: fail open or fail closed?). This is a classic trade-off. A middle ground: hold the payout (FUNDED → HELD) when an alert arrives before payout-worker dispatches. Left as an exercise.

## Simplifications
- The volume limit ignores currency conversion. Fix: normalise through fx-service rates.
- The thresholds are static config. Real systems use per-segment limits and models.
