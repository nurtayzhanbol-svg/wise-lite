CREATE TABLE transfers (
    id                UUID        PRIMARY KEY,
    owner_id          UUID        NOT NULL,
    source_account_id UUID        NOT NULL REFERENCES accounts (id),
    amount_minor      BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency          CHAR(3)     NOT NULL,
    recipient_name    TEXT        NOT NULL,
    recipient_iban    TEXT        NOT NULL,
    state             TEXT        NOT NULL CHECK (state IN ('CREATED', 'FUNDED', 'PROCESSING', 'COMPLETED', 'FAILED', 'REFUNDED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX transfers_owner_idx ON transfers (owner_id, created_at);

-- Audit trail of every state change. Support and debugging start here.
CREATE TABLE transfer_state_history (
    id          BIGSERIAL   PRIMARY KEY,
    transfer_id UUID        NOT NULL REFERENCES transfers (id),
    from_state  TEXT,
    to_state    TEXT        NOT NULL,
    reason      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX transfer_state_history_transfer_idx ON transfer_state_history (transfer_id, id);

-- Idempotency keys are scoped per client (owner): two clients may use the same key value.
-- The row is inserted in the same transaction as the business operation, so the key and its
-- effects commit or roll back together.
CREATE TABLE idempotency_keys (
    owner_id        UUID        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    response_status INT         NOT NULL,
    response_body   TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, idempotency_key)
);
