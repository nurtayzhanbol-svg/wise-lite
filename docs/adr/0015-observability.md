# ADR 0015: Observability — business metrics, tracing across Kafka, symptom alerts

## Status
Accepted (M8)

## Context
In a money-movement system, "the service is up" says little. The questions on-call has to answer are: *is money stuck, is money wrong, is a dependency failing?* Technical metrics (CPU, heap, HTTP 5xx) are necessary, but they don't answer those.

## Decisions
1. **Micrometer + Prometheus** in every service (`/actuator/prometheus`), with a common `application` tag.
2. **Business and health metrics** that map to failure modes we already modelled:

   | Metric | Failure mode it reveals |
   |---|---|
   | `wiselite_outbox_unpublished`, `..._oldest_unpublished_age_seconds` | relay/Kafka down: events stuck (ADR 0010) |
   | `wiselite_payouts{status}`, `wiselite_payouts_oldest_unresolved_age_seconds` | unknown outcomes piling up, MANUAL_REVIEW (ADR 0013) |
   | `wiselite_rails_calls_seconds{outcome}` (histogram) | rail latency/errors, classification mix |
   | `wiselite_rails_circuit_state` | rail outage, payouts paused |
   | `wiselite_rails_callbacks_total{outcome}` | duplicates (normal), BAD_SIGNATURE (attack/rotated secret), CONFLICT |
   | `wiselite_transfers_transitions_total{to}` | throughput, refund ratio |
   | `wiselite_recon_breaks_total{severity,type}` | money-level discrepancies (ADR 0014) |

3. **The transition counter increments only after commit** (`TransactionSynchronization.afterCommit`). Otherwise rolled-back work would be counted.
4. **Gauges that read DB state:**
   - Outbox gauges are queried on scrape. They are cheap thanks to the partial index.
   - Payout gauges are refreshed every 10 s with one GROUP BY. The trade-off is staleness vs DB load.
5. **Distributed tracing:** Micrometer Tracing → OpenTelemetry (OTLP) → Jaeger. Kafka observation is enabled, so `traceparent` travels in Kafka headers, and RestClient calls are traced. **Known gap:** the outbox relay publishes from a scheduler thread, so the trace started by `POST /transfers` is *not* continued. The fix is to store `traceparent` in an outbox column and restore it when publishing (TODO). This is a good interview discussion point.
6. **Alerts on symptoms** (`infra/prometheus/alerts.yml`): backlog age, unresolved payout age, MANUAL_REVIEW > 0, circuit open, CRITICAL recon breaks, signature failures. No CPU alerts.
7. **Local stack is opt-in:** `docker compose --profile observability up -d` starts Prometheus (:9090), Grafana (:3000, provisioned dashboard `wise-lite: money movement`) and Jaeger (:16686).

## Alternatives
- Logs-only: hard to alert on and to aggregate. Logs stay for detail; metrics are for detection.
- Per-payout high-cardinality labels (transfer id): never do this in Prometheus. Use traces/logs for that.
