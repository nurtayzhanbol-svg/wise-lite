# ADR-0001: Record architecture decisions

- Status: accepted
- Date: 2026-10-03

## Context
This repository is meant to be studied and defended in interviews. Code alone doesn't explain *why* a choice was made or which alternatives were rejected.

## Decision
Every significant decision gets a short ADR in `docs/adr/` (format: context → decision → alternatives → consequences). ADRs are immutable once accepted; changes are made by a new ADR that supersedes the old one.

## Consequences
- Decisions and trade-offs can be reviewed without reading the code.
- Small overhead per decision.
