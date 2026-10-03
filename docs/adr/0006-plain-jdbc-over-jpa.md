# ADR-0006: Plain SQL (Spring `JdbcClient`) instead of JPA/Hibernate

- Status: accepted
- Date: 2026-10-03

## Context
The core of this project is about locking, isolation, constraints and exactly which statements run inside a transaction.

## Decision
Persistence uses `JdbcClient` with explicit SQL in repository classes. Schema changes go through Flyway migrations.

## Alternatives
- **JPA/Hibernate:** less boilerplate, but dirty checking, flush ordering and lazy loading hide when and which SQL runs. That makes concurrency behaviour harder to reason about and to explain.
- **jOOQ:** type-safe SQL and a strong option; skipped to keep the dependency count low.

## Consequences
More mapping code, but every query and lock is visible in one place (`LedgerRepository`).
