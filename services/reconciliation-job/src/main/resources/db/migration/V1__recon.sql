CREATE TABLE recon_runs (
    id                 UUID        PRIMARY KEY,
    cutoff             TIMESTAMPTZ NOT NULL,
    started_at         TIMESTAMPTZ NOT NULL,
    finished_at        TIMESTAMPTZ NOT NULL,
    transfers_checked  INT         NOT NULL,
    rail_lines_checked INT         NOT NULL,
    breaks             INT         NOT NULL
);

CREATE TABLE recon_breaks (
    id        BIGSERIAL PRIMARY KEY,
    run_id    UUID      NOT NULL REFERENCES recon_runs (id),
    type      TEXT      NOT NULL,
    severity  TEXT      NOT NULL,
    reference TEXT      NOT NULL,
    details   TEXT      NOT NULL
);

CREATE INDEX recon_breaks_run_idx ON recon_breaks (run_id);
