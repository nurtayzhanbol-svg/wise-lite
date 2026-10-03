-- Double-entry ledger. The application enforces these rules too; the database is the
-- last line of defence (a bug, a manual SQL fix or a new service cannot break them).

CREATE TABLE accounts (
    id         UUID PRIMARY KEY,
    owner_id   UUID,
    currency   CHAR(3)     NOT NULL,
    type       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT customer_accounts_have_owner CHECK ((type = 'CUSTOMER') = (owner_id IS NOT NULL)),
    -- target for composite foreign keys below: an entry's currency must equal its account's currency
    CONSTRAINT accounts_id_currency_unique UNIQUE (id, currency)
);

CREATE UNIQUE INDEX one_customer_account_per_currency ON accounts (owner_id, currency) WHERE owner_id IS NOT NULL;
CREATE UNIQUE INDEX one_system_account_per_type_currency ON accounts (type, currency) WHERE owner_id IS NULL;

-- Projection of SUM(ledger_entries.amount_minor) per account, updated in the same transaction
-- as the entries. Gives O(1) balance reads and a row to lock when debiting.
CREATE TABLE account_balances (
    account_id     UUID        PRIMARY KEY,
    currency       CHAR(3)     NOT NULL,
    balance_minor  BIGINT      NOT NULL DEFAULT 0,
    allow_negative BOOLEAN     NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT balances_account_fk FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency),
    CONSTRAINT balance_not_negative CHECK (allow_negative OR balance_minor >= 0)
);

CREATE TABLE journal_entries (
    id         UUID        PRIMARY KEY,
    type       TEXT        NOT NULL,
    reference  TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX journal_entries_reference_idx ON journal_entries (reference);

CREATE TABLE ledger_entries (
    id               BIGSERIAL   PRIMARY KEY,
    journal_entry_id UUID        NOT NULL REFERENCES journal_entries (id),
    account_id       UUID        NOT NULL,
    currency         CHAR(3)     NOT NULL,
    amount_minor     BIGINT      NOT NULL CHECK (amount_minor <> 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT entries_account_fk FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
);

CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id);
CREATE INDEX ledger_entries_journal_idx ON ledger_entries (journal_entry_id);

-- Invariant I3: the ledger is append-only. Mistakes are corrected with new, reversing entries.
CREATE FUNCTION reject_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END
$$;

CREATE TRIGGER ledger_entries_append_only BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER journal_entries_append_only BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- Invariant I1: each journal entry balances per currency. Checked at COMMIT (deferred),
-- because entries are inserted one by one and are only balanced as a whole.
CREATE FUNCTION check_journal_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM ledger_entries
        WHERE journal_entry_id = NEW.journal_entry_id
        GROUP BY currency
        HAVING SUM(amount_minor) <> 0
    ) THEN
        RAISE EXCEPTION 'journal entry % is unbalanced', NEW.journal_entry_id;
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_entry_balanced();
