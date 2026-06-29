-- Phase 5a (access-owned): the access-governance read surfaces.
--
-- Two tables in the `access` schema (created in V1 baseline). Access *requests* themselves live in the
-- shared request aggregate (RequestType.ACCESS) — these are the access module's own data:
--
--   catalog        -- reference data: the grantable resources. mapped_group is the Entra group an approval
--                     grants membership to (5b provisions against it; 5a simulates). Read-only via the API
--                     in v1 (catalog management = a future admin CR).
--   access_grant   -- the my-access PROJECTION. The SOURCE OF TRUTH for "currently held" is removed_at IS
--                     NULL — NOT the request status (a removal request ends in GRANTED-meaning-"completed",
--                     since the frozen enums have no REMOVED state). Written atomically with markProvisioned
--                     by the access worker. expires_at is persisted now (= granted_at + duration) but v1
--                     does NOT enforce it — duration is informational; the expiry sweep is a future CR
--                     (CR-20260628-2240), and because the column exists that sweep needs no migration.

CREATE TABLE access.catalog (
    id           text PRIMARY KEY,
    name         text NOT NULL,
    type         text NOT NULL,                 -- AWS | WORKDAY | ROLE | TEAM
    risk         text NOT NULL,                 -- LOW | MEDIUM | HIGH
    description  text,
    mapped_group text NOT NULL                  -- Entra group an approval grants membership to
);

CREATE TABLE access.access_grant (
    id            uuid PRIMARY KEY,
    resource_id   text        NOT NULL REFERENCES access.catalog (id),
    resource_name text        NOT NULL,
    user_id       text        NOT NULL,
    request_id    uuid        NOT NULL UNIQUE,  -- one grant row per granting request (idempotent completion)
    granted_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz,                  -- granted_at + duration; NULL = permanent. Recorded, NOT enforced in v1.
    removed_at    timestamptz                   -- set when a removal is provisioned; NULL = currently held
);

-- my-access lookup ("what do I currently hold") + the at-most-one-active-grant-per-(user,resource) invariant.
CREATE UNIQUE INDEX access_grant_active_idx ON access.access_grant (user_id, resource_id) WHERE removed_at IS NULL;

-- Dev catalog seed. mapped_group values are placeholders for 5a (simulated provisioning makes no Graph
-- call); 5b binds them to real Entra group OBJECT IDs (manually created — see RUNBOOK / ADR-0017).
INSERT INTO access.catalog (id, name, type, risk, description, mapped_group) VALUES
    ('aws-prod',      'AWS Production',        'AWS',     'HIGH',   'Production AWS account access',        'aws-prod-engineers'),
    ('aws-dev',       'AWS Development',       'AWS',     'LOW',    'Development AWS account access',       'aws-dev-engineers'),
    ('workday-rep',   'Workday Reporting',     'WORKDAY', 'MEDIUM', 'Workday reporting role',              'workday-reporting'),
    ('role-approver', 'Approver (governance)', 'ROLE',    'HIGH',   'Governance group: request approvers', 'eop-approvers'),
    ('team-platform', 'Platform Team',         'TEAM',    'LOW',    'Platform engineering team membership', 'team-platform');
