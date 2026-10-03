-- Baseline migration. Real schema arrives in M1 (ledger).
CREATE TABLE schema_info (
    id          SMALLINT PRIMARY KEY,
    description TEXT NOT NULL
);

INSERT INTO schema_info (id, description) VALUES (1, 'wise-lite transfer-service baseline');
