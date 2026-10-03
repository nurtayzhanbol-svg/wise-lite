# wise-lite

A simplified cross-border money-movement platform, built to study the engineering problems behind products like Wise: ledger correctness, idempotency, concurrency, reliable messaging, unreliable payment rails, reconciliation, and observability.

> This is a learning project. Every component exists to demonstrate a specific engineering problem and trade-off; see [`docs/architecture.md`](docs/architecture.md) and the ADRs in [`docs/adr`](docs/adr).

## Status

| Milestone | Topic | Status |
|-----------|-------|--------|
| M0 | Monorepo skeleton, CI, local infra, Testcontainers | ✅ |
| M1 | Ledger core (double-entry) | ✅ |
| M2 | Transfers, state machine, idempotency | ✅ |
| M3 | Concurrency & locking | ✅ |
| M4 | Transactional outbox → Kafka | ⏳ |
| M5 | fx-service | ⏳ |
| M6 | rails-simulator + payout-worker | ⏳ |
| M7 | Reconciliation | ⏳ |
| M8 | Observability, load, chaos | ⏳ |
| M9 | risk-engine (Kafka Streams) | ⏳ |

## Quick start

Requirements: JDK 21, Docker.

```bash
./gradlew build          # compiles and runs all tests (Testcontainers starts Postgres/Kafka itself)
make up                  # start Postgres + Kafka locally
make run-transfer        # run transfer-service on :8080
curl localhost:8080/actuator/health
```

## Repository layout

```
services/
  transfer-service/   ledger, transfers, idempotency, outbox
docs/
  architecture.md     system overview and diagrams
  adr/                architecture decision records
  interview/          "explain and defend it" notes per topic
  invariants.md       properties the system must always hold
  failure-scenarios.md
  STUDY-GUIDE.md      suggested reading/rebuild order
  TODO-for-me.md      exercises to do by hand
```

### Try the M2 flow
```bash
make up && make run-transfer   # in another terminal:
OWNER=$(uuidgen)
ACC=$(curl -s localhost:8080/accounts -H 'Content-Type: application/json' -d "{\"ownerId\":\"$OWNER\",\"currency\":\"EUR\"}" | jq -r .id)
curl -s localhost:8080/internal/accounts/$ACC/top-ups -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"amount":100,"currency":"EUR"}'
KEY=$(uuidgen)
for i in 1 2; do  # second call is a replay: same body, Idempotent-Replayed: true
  curl -si localhost:8080/transfers -H 'Content-Type: application/json' -H "X-Owner-Id: $OWNER" -H "Idempotency-Key: $KEY" \
    -d "{\"sourceAccountId\":\"$ACC\",\"amount\":30,\"currency\":\"EUR\",\"recipient\":{\"name\":\"Bob\",\"iban\":\"DE89370400440532013000\"}}" | grep -i -e replayed -e '"id"'
done
```
