-- Phase 3b (platform-owned): the shared transactional outbox. Domain events from any module are
-- appended here in the SAME tx as the state change, then dispatched by the Phase 6 relay (single
-- reader: FOR UPDATE SKIP LOCKED). It lives in a neutral `messaging` schema so no module reads or
-- writes another module's tables — every append goes through platform.OutboxWriter (ArchUnit-enforced
-- in Java), and the relay reads this one place.

CREATE SCHEMA IF NOT EXISTS messaging;

CREATE TABLE messaging.outbox (
    id              uuid PRIMARY KEY,
    aggregate_type  text        NOT NULL,
    aggregate_id    text        NOT NULL,
    event_type      text        NOT NULL,
    payload         jsonb       NOT NULL,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz,
    attempts        int         NOT NULL DEFAULT 0,
    next_attempt_at timestamptz
);

-- The relay claims unpublished rows; a partial index keeps that scan tiny as published rows accumulate.
CREATE INDEX outbox_unpublished_idx ON messaging.outbox (occurred_at) WHERE published_at IS NULL;
