# ADR-0003: Integration tests against real Postgres and Kafka via Testcontainers

- Status: accepted
- Date: 2026-10-03

## Context
The most important claims in this project — "no double spend", "no lost events", "idempotent retries" — depend on database locking, isolation levels, constraints and broker behaviour. In-memory substitutes (H2, embedded fakes) behave differently in exactly those areas.

## Decision
Integration tests use Testcontainers (`postgres:16-alpine`, `apache/kafka`) wired through Spring Boot's `@ServiceConnection`. Pure domain logic is still unit-tested without containers.

## Alternatives
- **H2 in Postgres mode:** fast, but different locking/MVCC semantics; would make concurrency tests meaningless.
- **Shared dev database:** non-reproducible, flaky in CI.

## Consequences
- Tests need Docker (available on GitHub Actions runners).
- Slower than pure unit tests; keep the number of container-backed test classes moderate and reuse containers.
- Testcontainers is pinned to 1.21.4 (above Spring Boot 3.3's managed 1.19.8): older versions default to Docker API 1.32, which Docker Engine 29+ rejects ("client version 1.32 is too old").
