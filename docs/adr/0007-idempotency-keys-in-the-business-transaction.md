# ADR 0007: Idempotency keys stored in the business transaction

## Status
Accepted (M2)

## Context
Clients retry `POST /transfers` after timeouts. Without protection, a retry after a lost response creates a second transfer and debits the customer twice.

## Decision
- Clients send an `Idempotency-Key` header. Keys are scoped per owner: primary key `(owner_id, idempotency_key)`.
- `IdempotencyService.execute` inserts the key row (`INSERT ... ON CONFLICT DO NOTHING`) **in the same DB transaction** as the business operation, then stores the response.
- A concurrent request with the same key blocks on the unique index until the first transaction ends. If the first commits, the second replays the stored response. If it rolls back, the second runs the operation.
- The request fingerprint (SHA-256 of the *parsed* command) is stored. If the same key arrives with a different request, the API returns 422.
- Only successful responses are stored. A failed request rolls back the key too, so retrying it is safe: the failed attempt had no side effects.
- Replays return `Idempotent-Replayed: true`.

## Alternatives considered
- **An `IN_PROGRESS` row committed up front, then the operation in a second transaction (Stripe-style).** This is needed when the operation calls external systems and you can't hold a DB transaction open. It brings stuck keys after crashes and needs a recovery/timeout policy. Not needed here: the operation is purely local, and external calls are asynchronous (M4/M6).
- **Storing 4xx outcomes as well.** This gives stricter "same key → same answer" semantics, but makes a retry fail forever even after the cause is fixed (e.g. after a top-up). It also needs a savepoint so the key can commit while the business change rolls back.
- **Redis `SETNX`.** It is a second system that is not transactional with Postgres, which reintroduces the dual-write problem.
- **Natural-key uniqueness only** (e.g. a unique client reference column on `transfers`). This works for one endpoint but gives no response replay and no body-mismatch detection.

## Consequences
- A transaction stays open for the whole request. That is acceptable because no network calls happen inside it.
- The keys table grows without bound. TODO: add a TTL cleanup job (e.g. delete rows older than 24h–7d) and document the client contract.
