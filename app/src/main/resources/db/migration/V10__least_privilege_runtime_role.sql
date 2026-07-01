-- Phase 10-1 (ADR-0026): least-privilege runtime DB role — the ENGINE-level guarantee behind the V8
-- audit immutability trigger.
--
-- Splits "migrate" from "run":
--   • Flyway keeps connecting as the RDS master (this migration runs as master — it needs DDL + role admin).
--   • The APP runtime connects as `eop_app`, which can read everything the app touches and write the mutable
--     schemas, but has NO UPDATE/DELETE on the append-only audit log. So even if a future bug (or an attacker
--     who reached the app's DB connection) tried to rewrite history, the DB rejects it on privilege grounds —
--     independent of, and underneath, the V8 BEFORE UPDATE/DELETE trigger.
--
-- `eop_app` authenticates via RDS IAM database auth (no stored password; the ECS task role holds
-- `rds-db:connect`) — on-theme with the project's zero-stored-credentials identity (WIF/OIDC everywhere).
-- The `rds_iam` GRANT is GUARDED so this migration also runs on vanilla Postgres (Testcontainers/CI), where
-- the RDS-managed `rds_iam` role does not exist.
--
-- Idempotent throughout (guarded CREATE ROLE, IF-EXISTS rds_iam) so it is safe to re-run across environments
-- and concurrent-boot Flyway.

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'eop_app') THEN
    CREATE ROLE eop_app WITH LOGIN;
  END IF;
END $$;

-- RDS IAM auth (token login; no password). Guarded for non-RDS environments (CI/local vanilla Postgres).
DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'rds_iam') THEN
    GRANT rds_iam TO eop_app;
  END IF;
END $$;

-- ---- Read the world the app touches (all app schemas, incl. audit for GET /audit + the relay's tail read).
GRANT USAGE ON SCHEMA public, messaging, request, platform, access, teams, notify, audit TO eop_app;
GRANT SELECT ON ALL TABLES IN SCHEMA messaging, request, platform, access, teams, notify, audit TO eop_app;

-- ---- Write the MUTABLE schemas (everything except audit).
GRANT INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA messaging, request, platform, access, teams, notify TO eop_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA messaging, request, platform, access, teams, notify TO eop_app;

-- ---- Audit (append-only): INSERT + SELECT ONLY. The relay appends; nobody updates or deletes. The REVOKE is
-- explicit defense-in-depth on top of the by-omission absence, and includes TRUNCATE (which bypasses the row
-- trigger). NOTE: this REVOKE trips the soft migration guard's TRUNCATE keyword (ADR-0025) — it is a *revoke*,
-- the opposite of destructive; the guard is advisory only.
GRANT INSERT ON audit.audit_events TO eop_app;
REVOKE UPDATE, DELETE, TRUNCATE ON audit.audit_events FROM eop_app;

-- ---- Future tables/sequences: default privileges so a new migration doesn't have to remember to grant
-- eop_app (forgetting would break the app at runtime on the new table). Defaults apply to objects created by
-- the CURRENT role (master = the Flyway migrator), which is exactly who future migrations run as. Ties to the
-- ADR-0025 expand/contract discipline: adding a table is expand-safe AND privilege-safe by default.
ALTER DEFAULT PRIVILEGES IN SCHEMA messaging, request, platform, access, teams, notify
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO eop_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA messaging, request, platform, access, teams, notify
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO eop_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA audit
  GRANT SELECT, INSERT ON TABLES TO eop_app;
