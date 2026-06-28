-- Phase 3b (request-owned): the shared request aggregate + its timeline.
--
-- Identity columns (requester/submitted_by) are first-class, NOT inside payload, so SoD/ABAC never
-- parse JSON. `version` is the optimistic-concurrency token (the ETag) AND the guard column for the
-- authoritative serializer: UPDATE ... WHERE id=:id AND status=:from AND version=:expected succeeds for
-- exactly one caller. `external_ref` holds the provisioned resource id (e.g. the Entra client id) so a
-- provisioning retry is find-or-create, not a blind create (idempotency hook for Phase 4).

CREATE TABLE request.requests (
    id           uuid PRIMARY KEY,
    type         text        NOT NULL, -- ONBOARDING | ACCESS
    status       text        NOT NULL,
    requester    text        NOT NULL,
    submitted_by text        NOT NULL,
    approver     text,
    reason       text,
    payload      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    external_ref text,
    version      int         NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX requests_status_idx    ON request.requests (status); -- review queue + provisioning poller
CREATE INDEX requests_requester_idx ON request.requests (requester);

-- Append-only timeline. A global bigserial id gives total order (gaps are fine), so a non-transition
-- entry (e.g. PROVISIONING_FAILED, emitted off the version-serialized path) appends safely under
-- concurrency. from_status/to_status are nullable: a non-transition event carries event_type alone.
CREATE TABLE request.request_events (
    id             bigserial PRIMARY KEY,
    request_id     uuid        NOT NULL REFERENCES request.requests (id),
    from_status    text,
    to_status      text,
    event_type     text        NOT NULL,
    actor          text        NOT NULL,
    effective_role text,
    reason         text,
    at             timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX request_events_request_idx ON request.request_events (request_id, id);
