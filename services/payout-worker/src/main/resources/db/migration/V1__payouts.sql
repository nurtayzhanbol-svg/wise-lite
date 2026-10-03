-- Dedupe table for the idempotent consumer: an event id is recorded in the same transaction as
-- its effect, so a redelivered event is detected and skipped.
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One payout per transfer, enforced by the primary key: a second line of defence even if two
-- *different* events ask for the same payout.
CREATE TABLE payouts (
    transfer_id    UUID        PRIMARY KEY,
    amount_minor   BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency       CHAR(3)     NOT NULL,
    recipient_name TEXT        NOT NULL,
    recipient_iban TEXT        NOT NULL,
    status         TEXT        NOT NULL CHECK (status IN ('PENDING')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
