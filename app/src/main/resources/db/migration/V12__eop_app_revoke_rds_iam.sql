-- Phase 10-1 Stage 2 fix (#175/#176): eop_app must NOT be an rds_iam member for PASSWORD auth to work.
--
-- V10 granted rds_iam to eop_app for the (preferred) IAM-token login. But RDS's IAM pg_hba rule
--     hostssl all +rds_iam all pam
-- routes EVERY SSL connection from an rds_iam member through PAM (IAM-token) auth. So once eop_app is an
-- rds_iam member, a *password* login for it fails with "PAM authentication failed for user eop_app" — which is
-- exactly why the Stage 2 password rollout crash-looped (the app's SSL connection matched the +rds_iam pam rule
-- and its password was rejected; the non-SSL fallback was then rejected for "no encryption").
--
-- Since Stage 2 authenticates eop_app with a managed password (IAM-token login is blocked in this env, #175),
-- revoke rds_iam so eop_app's SSL connections fall through to the normal password rule (hostssl … scram-sha-256).
-- This supersedes V10's grant. To retry IAM auth later you would re-grant rds_iam AND resolve the #175 authz
-- failure — the two auth modes are mutually exclusive for a given role.
--
-- Guarded IF EXISTS so it also runs on vanilla Postgres (CI/Testcontainers, where rds_iam doesn't exist and
-- eop_app was never granted it). REVOKE of a non-membership is a harmless no-op.

DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'rds_iam') THEN
    REVOKE rds_iam FROM eop_app;
  END IF;
END $$;
