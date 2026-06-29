-- Phase 5c (teams-owned): teams + membership. Direct CRUD — NOT over the request aggregate (no state
-- machine, no approval), so its own schema + tables. The `teams` schema isn't in the V1 baseline (teams
-- was a later slice), so create it here.
--
-- NOTE: the frozen contract has NO `DELETE /teams/{id}` — only member soft-delete
-- (`DELETE /teams/{id}/members/{userId}`). So there is intentionally no `teams.deleted_at`/cascade; team
-- deletion would be a contract change (deferred). Member soft-delete keeps the row (removed_at) so the
-- outbox audit events remain the history and the row is current-state — same idiom as access_grant.

CREATE SCHEMA IF NOT EXISTS teams;

CREATE TABLE teams.teams (
    id          uuid PRIMARY KEY,
    name        text        NOT NULL UNIQUE,  -- tenant-unique team name → 409 on dup (like onboarding)
    description text,
    owner       text        NOT NULL,         -- creator oid; ABAC ownerId
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE teams.team_members (
    id         uuid PRIMARY KEY,
    team_id    uuid        NOT NULL REFERENCES teams.teams (id),
    user_id    text        NOT NULL,          -- member oid
    added_at   timestamptz NOT NULL DEFAULT now(),
    removed_at timestamptz                    -- NULL = active; set on soft-delete (re-add reactivates)
);

-- At most one ACTIVE membership per (team, user) — the atomic guard for add/reactivate (same as access_grant).
CREATE UNIQUE INDEX team_members_active_idx ON teams.team_members (team_id, user_id) WHERE removed_at IS NULL;
CREATE INDEX team_members_team_idx ON teams.team_members (team_id);
