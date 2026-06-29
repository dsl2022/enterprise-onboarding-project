-- Phase 4a (platform-owned): Idempotency-Key store for creating/transition POSTs. The PK
-- (principal, endpoint, idempotency_key) IS the claim lock — a winner INSERTs PENDING before
-- processing, fills in the response on success, and the row is deleted on failure (so a transient
-- error stays retryable). Replay returns the stored response; same key + different body → 422.
-- Per-principal + per-endpoint, 24h window (stale rows are reclaimed at claim time).

CREATE SCHEMA IF NOT EXISTS platform;

CREATE TABLE platform.idempotency_keys (
    principal       text        NOT NULL,
    endpoint        text        NOT NULL,
    idempotency_key text        NOT NULL,
    request_hash    text        NOT NULL,
    status          text        NOT NULL, -- PENDING | COMPLETE
    response_status int,
    response_etag   text,
    response_body   text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (principal, endpoint, idempotency_key)
);
