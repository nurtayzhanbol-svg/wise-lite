# Study guide

Suggested order once the sprint is over:

1. Read `docs/architecture.md`, then the ADRs in order.
2. For each milestone (tags `m1`, `m2`, …): `git checkout mN`, read the matching `docs/interview/*.md`, run the tests, then do the rebuild exercise from that file.
3. Break things on purpose: remove a lock, an idempotency check, the outbox. Watch which test fails, and make sure you can explain why.
4. Practise explaining each component out loud in 2 minutes: problem, solution, trade-off.
