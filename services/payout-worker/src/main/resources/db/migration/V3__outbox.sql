-- Transactional outbox: events are written in the same transaction as the state change they
-- describe, then published to Kafka by OutboxRelay. seq gives a total order for publishing.
CREATE TABLE outbox_events (
    seq          BIGSERIAL   PRIMARY KEY,
    event_id     UUID        NOT NULL UNIQUE,
    topic        TEXT        NOT NULL,
    event_key    TEXT        NOT NULL,
    event_type   TEXT        NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

-- The relay only ever scans unpublished rows; a partial index keeps that cheap as the table grows.
CREATE INDEX outbox_unpublished_idx ON outbox_events (seq) WHERE published_at IS NULL;
