-- Phase 10-1 Stage 2 (ADR-0026): set the eop_app runtime role's login password.
--
-- IAM database auth was the preferred credential mechanism (V10 granted rds_iam; the instance + task role are
-- wired for it), but its token login is blocked in this environment (#175 — RDS returns PAM "Permission denied"
-- despite a verified-correct rds-db:connect policy). So eop_app authenticates with a managed password instead —
-- the same pattern as the RDS master user. The privilege model is unchanged: eop_app is still append-only on
-- the audit log (V10's REVOKE stands); only the LOGIN mechanism differs.
--
-- Runs as the migrator (master). The password comes from the Flyway placeholder ${eop_app_password}, fed from
-- the CMK-encrypted Secrets Manager secret the app datasource also reads (application-data.yml) — so the role's
-- password and the datasource's password are always the same value, sourced once. The placeholder has a
-- test-safe default ("test") so CI/Testcontainers (which never connect AS eop_app) stay green.
--
-- Idempotent: ALTER ROLE ... PASSWORD is safe to re-run. The rds_iam grant from V10 is intentionally left in
-- place — harmless alongside a password, and it keeps the door open to switch back to IAM auth with no migration.

ALTER ROLE eop_app WITH PASSWORD '${eop_app_password}';
