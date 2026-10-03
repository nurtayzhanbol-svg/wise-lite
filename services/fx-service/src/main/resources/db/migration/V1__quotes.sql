CREATE TABLE quotes (
    id                  UUID          PRIMARY KEY,
    source_currency     CHAR(3)       NOT NULL,
    target_currency     CHAR(3)       NOT NULL,
    source_amount_minor BIGINT        NOT NULL CHECK (source_amount_minor > 0),
    fee_minor           BIGINT        NOT NULL CHECK (fee_minor >= 0),
    rate                NUMERIC(20,8) NOT NULL CHECK (rate > 0),
    target_amount_minor BIGINT        NOT NULL CHECK (target_amount_minor > 0),
    rate_as_of          DATE          NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    expires_at          TIMESTAMPTZ   NOT NULL,
    used_at             TIMESTAMPTZ,
    used_by             TEXT,
    CONSTRAINT fee_below_amount CHECK (fee_minor < source_amount_minor),
    CONSTRAINT expiry_after_creation CHECK (expires_at > created_at),
    CONSTRAINT different_currencies CHECK (source_currency <> target_currency),
    CONSTRAINT used_together CHECK ((used_at IS NULL) = (used_by IS NULL))
);
