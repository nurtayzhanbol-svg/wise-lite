-- Payout lifecycle:
--   PENDING -> SUBMITTED (rail accepted) -> SETTLED | REJECTED   (via webhook or re-submit)
--   PENDING/SUBMITTED -> UNKNOWN (timeout / 5xx: we do NOT know if money moved) -> retried
--   any non-terminal -> MANUAL_REVIEW after max attempts (never auto-FAILED: money may have moved)
ALTER TABLE payouts DROP CONSTRAINT payouts_status_check;
ALTER TABLE payouts ADD CONSTRAINT payouts_status_check
    CHECK (status IN ('PENDING', 'SUBMITTED', 'UNKNOWN', 'SETTLED', 'REJECTED', 'MANUAL_REVIEW'));

ALTER TABLE payouts
    ADD COLUMN attempts         INT         NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN lease_until      TIMESTAMPTZ,
    ADD COLUMN rails_payment_id TEXT,
    ADD COLUMN last_error       TEXT,
    ADD COLUMN updated_at       TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX payouts_due_idx ON payouts (next_attempt_at)
    WHERE status IN ('PENDING', 'SUBMITTED', 'UNKNOWN');
