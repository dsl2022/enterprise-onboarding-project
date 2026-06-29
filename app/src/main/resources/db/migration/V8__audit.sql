-- Phase 6a (audit-owned): the hash-chained, append-only audit log + the immutability guard.
--
-- The single leader-elected outbox relay (platform.OutboxRelay) projects each messaging.outbox row into
-- exactly one audit row, in occurred_at order, building a hash chain (hash = H(prevHash ‖ canonical(row))).
-- Single-writer is mandatory: the chain is linear, so two appenders would fork it (the relay holds a
-- Postgres advisory lock to guarantee one writer across N Fargate tasks).

CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE audit.audit_events (
    id              uuid PRIMARY KEY,
    -- Contract `seq` (int64). GENERATED ALWAYS AS IDENTITY consumes values on rollback (our 23505
    -- idempotency path rolls back a duplicate insert), so seq is monotonic but NOT gap-free. Chain
    -- integrity comes from prev_hash linkage, never seq contiguity — /audit/verify walks ORDER BY seq
    -- linking prev_hash and tolerates gaps. No client may assume gap-freeness.
    seq             bigint      GENERATED ALWAYS AS IDENTITY,
    -- = messaging.outbox.id. UNIQUE makes reprocessing idempotent: a relay that crashes after the insert
    -- but before marking the outbox row published re-attempts and hits this constraint (23505 = already
    -- audited). At-least-once delivery, exactly-once effect.
    source_event_id uuid        NOT NULL UNIQUE,
    actor           text        NOT NULL,   -- REAL principal (Super Admin even while impersonating)
    effective_role  text,                   -- impersonated/effective role; nullable (system + legacy rows)
    action          text        NOT NULL,   -- = outbox event_type (e.g. request.approved, team.member.added)
    resource_type   text        NOT NULL,   -- = outbox aggregate_type (request, team)
    resource_id     text        NOT NULL,   -- = outbox aggregate_id
    at              timestamptz NOT NULL,   -- WHEN IT HAPPENED = outbox occurred_at (decision 4)
    audited_at      timestamptz NOT NULL DEFAULT now(), -- when the relay chained it; for lag monitoring only
    detail          jsonb       NOT NULL DEFAULT '{}',  -- the full outbox payload (carries RequestType, etc.)
    prev_hash       text        NOT NULL,   -- hash of seq-1's row; genesis = a fixed constant
    hash            text        NOT NULL
);

-- /audit/verify and GET /audit both read in chain order.
CREATE UNIQUE INDEX audit_seq_idx ON audit.audit_events (seq);
-- GET /audit filters: actor / action / resourceType / time window.
CREATE INDEX audit_filter_idx ON audit.audit_events (at DESC, seq DESC);

-- Immutability (decision 2, the trigger half — belt-and-suspenders now; the DB-role REVOKE rides Phase 10
-- least-priv). The app literally cannot rewrite history regardless of which role it connects as.
CREATE OR REPLACE FUNCTION audit.deny_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit.audit_events is append-only (% denied)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_events_immutable
    BEFORE UPDATE OR DELETE ON audit.audit_events
    FOR EACH ROW EXECUTE FUNCTION audit.deny_mutation();

-- Decision 5 (pre-6a backlog): messaging.outbox has accumulated rows from 4b/5b/5c live runs, emitted
-- BEFORE actor-enrichment exists — so they carry degraded attribution and are throwaway test data. Rather
-- than audit them with a fallback actor (polluting the permanent chain), fast-forward them to published so
-- the chain starts clean from the first post-deploy event. The relay still carries a missing-actor fallback
-- for any row an old task emits mid-deploy. (published_at is consumed ONLY by the relay; provisioning reads
-- request status, not the outbox, so this has zero effect on in-flight provisioning.)
UPDATE messaging.outbox SET published_at = now() WHERE published_at IS NULL;
