-- M10: risk gate. FUNDED now means "money reserved, waiting for a risk decision".
ALTER TABLE transfers DROP CONSTRAINT transfers_state_check;
ALTER TABLE transfers ADD CONSTRAINT transfers_state_check
    CHECK (state IN ('CREATED', 'FUNDED', 'HELD', 'APPROVED', 'PROCESSING', 'COMPLETED', 'FAILED', 'REFUNDED'));

-- Every risk decision we received or made, applied or not. The audit trail an operator looks at
-- ("the engine said ALLOW 7 minutes after we timed out and held it").
CREATE TABLE risk_decisions (
    id           BIGSERIAL   PRIMARY KEY,
    transfer_id  UUID        NOT NULL REFERENCES transfers (id),
    decision_id  UUID        NOT NULL UNIQUE,
    source       TEXT        NOT NULL CHECK (source IN ('ENGINE', 'TIMEOUT', 'OPERATOR')),
    decision     TEXT        NOT NULL CHECK (decision IN ('ALLOW', 'REVIEW', 'BLOCK')),
    reasons      TEXT,
    outcome      TEXT        NOT NULL CHECK (outcome IN ('APPLIED', 'IGNORED')),
    state_before TEXT        NOT NULL,
    actor        TEXT,
    decided_at   TIMESTAMPTZ NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX risk_decisions_transfer_idx ON risk_decisions (transfer_id, id);

-- At most one *applied* decision per source per transfer: one automated verdict, one timeout hold,
-- one operator verdict. A second one getting applied would be a bug; the database refuses it.
CREATE UNIQUE INDEX one_applied_decision_per_source ON risk_decisions (transfer_id, source) WHERE outcome = 'APPLIED';

-- The timeout sweeper only scans transfers waiting for a decision.
CREATE INDEX transfers_awaiting_risk_idx ON transfers (updated_at) WHERE state = 'FUNDED';
