# System test under chaos (M8)

`./gradlew systemTest` (or `make system-test`) runs `tests/system/.../ChaosSystemTest`:

1. Starts Postgres and Kafka (Testcontainers), then runs **the real boot jars** of transfer-service, payout-worker, rails-simulator and reconciliation-job as separate OS processes. Logs go to `tests/system/build/system-test-logs/`.
2. Turns on rail faults:
   - 10 % fail before processing;
   - 20 % fail *after* processing (unknown outcome);
   - 10 % rejects;
   - 20 ms latency;
   - every webhook delivered 3 times.
3. Opens 10 accounts and sends 200 transfers from 16 threads. **Each request is sent twice with the same Idempotency-Key.**
4. Chaos during the load:
   - Kafka is `docker pause`d for 5 s;
   - payout-worker is killed with SIGKILL and restarted. In-flight leases expire, and webhooks sent while it was down are lost; the callback timeout re-asks the rail.
5. Asserts:
   - every transfer reaches COMPLETED or REFUNDED;
   - **money is conserved**: customers lost exactly the completed amounts;
   - **at most one rail payment per transfer**, and the set of SETTLED rail payments equals the set of COMPLETED transfers;
   - **reconciliation reports zero breaks.**

## Why this test matters
Unit and integration tests prove each mechanism in isolation. This test proves that their *composition* holds when things fail together. The pieces involved:
- idempotency keys (ADR 0007);
- the outbox (ADR 0010);
- consumer dedupe (ADR 0011);
- leases and unknown outcomes (ADR 0013);
- reconciliation (ADR 0014).

It runs in CI as a separate job after `build`.

## Exercises
- Raise `failAfterRate` to 0.6 and observe MANUAL_REVIEW. Then decide what the test should assert in that case.
- Kill transfer-service instead (the outbox relay restarts; duplicate publishes are possible).
- Add a k6/Gatling script against a `docker compose` deployment and watch the Grafana dashboard.

## Risk gate across real processes (M10)

`RiskGateSystemTest` starts the same processes plus `risk-engine` (shared setup in `SystemHarness`) with review limit 300.00 EUR and block limit 600.00 EUR:

1. ALLOW / REVIEW / BLOCK end to end; HELD and BLOCKed transfers have **zero rail payments**; 8 concurrent releases → one rail payment; 8 concurrent rejects → one refund.
2. Release racing reject on 6 HELD transfers → exactly one verdict wins each time.
3. Every decision on `risk.decisions.v1` is re-published, plus conflicting decisions for a COMPLETED, REFUNDED and HELD transfer → nothing changes (state, balance, rail statement).
4. risk-engine SIGKILLed → transfer times out to HELD (fail closed), the late decision after restart is recorded IGNORED, operator release pays once.
5. transfer-service and payout-worker SIGKILLed while 30 transfers are being decided → one applied decision and ≤ 1 rail payment each.
6. Reconciliation is clean.

The chaos test also runs through the gate now (all its amounts are ALLOWed).
