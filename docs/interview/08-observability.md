# Observability (M8)

Code: `OutboxMetrics` (libs/outbox), `PayoutMetrics`, `RailsClient` (timer), `RailsCallbackController` (counter), `TransferService.advance` (after-commit counter), `ReconciliationService`. Config: `infra/prometheus/*`, `infra/grafana/*`, `docker-compose.yml` profile `observability`. Test: `PayoutDispatchIT.operationalMetricsAreExposedForPrometheus`.

## 1. Things to be able to explain
1. **Symptoms vs causes:** alert on "money is stuck" (age of the oldest unresolved item), not on "CPU is 90 %". Age beats count: a large, moving backlog is healthy; a small stuck one is not.
2. **RED and USE:**
   - RED = rate, errors, duration, for request-driven work (HTTP, rail calls).
   - USE = utilization, saturation, errors, for resources (DB pool, consumer lag).
3. **Cardinality:** labels must be bounded (status, outcome, severity). Never transfer ids. Every label combination is a separate time series.
4. **Histograms vs averages:** p99 comes from histogram buckets (`histogram_quantile`). Averages hide the tail.
5. **Metrics inside transactions:** increment after commit, or the dashboard lies during rollbacks and retries.
6. **Tracing across async boundaries:**
   - HTTP and Kafka propagate `traceparent` automatically.
   - Scheduler threads (outbox relay, dispatcher) start new traces.
   - To link them, persist the context next to the work item (outbox row, payout row).
7. **SLOs for this system (proposal):**
   - 99 % of transfers funded within 1 s of the request.
   - 99 % of payouts with a final outcome within 15 min (excluding rail outages).
   - 0 CRITICAL recon breaks.

   "Meaningful availability" (Wise blog) means measuring what users experience, not server uptime.

## 2. Likely questions
- "What would you put on the on-call dashboard?" → The panels in `infra/grafana/dashboards/wise-lite.json`, top to bottom: throughput, backlog, payout states, rail health, recon.
- "Consumer lag?" → Kafka exporter / `kafka_consumergroup_lag`. Alert on lag *growth*, not on an absolute value.
- "How do you debug one customer's stuck transfer?" → Trace or log by transfer id, then the payout row (`last_error`, `attempts`), then the rail's `GET /payments/{key}`.

## 3. Exercises
1. Add a `traceparent` column to `outbox_events` and continue the trace in `OutboxRelay`, so Jaeger shows the full path from `POST /transfers` to payout.
2. Add a DB-pool saturation alert (`hikaricp_connections_pending`).
3. Write burn-rate alerts for the payout SLO (multi-window, multi-burn-rate).
