-- Phase 6b (notify-owned): the in-app notification feed + the SECOND, independent outbox consumer.
--
-- Notifications are derived from the same messaging.outbox as audit, but by a SEPARATE consumer
-- (notify.NotifyRelay) so notify can NEVER stall the audit hash chain (and vice versa). The two consumers
-- track their own completion + backoff columns; a row is fully consumed only when BOTH published_at (audit,
-- V8) and notified_at (notify, here) are set. Notify claims with FOR UPDATE SKIP LOCKED (order-independent
-- fan-out), so it gets its own attempt counters distinct from audit's.

CREATE SCHEMA IF NOT EXISTS notify;

CREATE TABLE notify.notifications (
    id              uuid PRIMARY KEY,
    source_event_id uuid        NOT NULL,   -- = messaging.outbox.id
    recipient       text        NOT NULL,   -- the REAL principal oid the notification is for
    type            text        NOT NULL,   -- = outbox event_type (e.g. request.approved)
    title           text        NOT NULL,
    body            text        NOT NULL,
    resource_ref    text,                   -- the request/team id this is about (nullable)
    read            boolean     NOT NULL DEFAULT false,
    -- WHEN IT HAPPENED = the event's occurred_at, NOT the notify-row insert time. With parallel/lagging
    -- SKIP-LOCKED consumers, ordering by insert time would dump a clump of "now"-stamped rows at the top of
    -- the feed after a stall — stale events masquerading as new. occurred_at keeps the feed causally stable.
    created_at      timestamptz NOT NULL,
    -- Idempotent fan-out: at-least-once delivery, exactly-once per recipient (ON CONFLICT DO NOTHING).
    UNIQUE (source_event_id, recipient)
);

-- Feed read path: a recipient's notifications newest-first.
CREATE INDEX notifications_feed_idx ON notify.notifications (recipient, created_at DESC, id DESC);
-- unreadCount.
CREATE INDEX notifications_unread_idx ON notify.notifications (recipient) WHERE read = false;

-- The notify consumer's own markers on the shared outbox — independent of audit's published_at / attempts /
-- next_attempt_at (V2/V8), so the two consumers' backoff never collide.
ALTER TABLE messaging.outbox ADD COLUMN notified_at            timestamptz;
ALTER TABLE messaging.outbox ADD COLUMN notify_attempts        int NOT NULL DEFAULT 0;
ALTER TABLE messaging.outbox ADD COLUMN notify_next_attempt_at timestamptz;

-- Keeps the notify claim scan tiny as consumed rows accumulate.
CREATE INDEX outbox_unnotified_idx ON messaging.outbox (occurred_at) WHERE notified_at IS NULL;

-- Backlog fast-forward (load-bearing, same rationale as V8 for audit): mark every existing outbox row
-- notified so the notify consumer starts CLEAN — otherwise on first 6b deploy it would fan out a retroactive
-- burst of notifications for every historical event (the 4b/5b/5c test runs + the 6a live-verify seq-1 row).
UPDATE messaging.outbox SET notified_at = now() WHERE notified_at IS NULL;
