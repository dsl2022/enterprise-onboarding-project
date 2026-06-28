-- Phase 2 baseline: the persistence substrate every later v1 module builds on.
--
-- pgvector is enabled here (cheap; it will back the assistant's embeddings later). One schema per owning
-- module enforces the "each module owns its tables, no cross-module table access" boundary at the
-- database level — TABLES are added in each module's own phase, not now.
--
-- The `assistant` schema is intentionally NOT created yet: the assistant is a deferred track (Phase 7
-- ships only a 501 stub), so its schema lands with its own migration when that track starts (CR-1416).
--
-- The audit-specific locked-down DB role (INSERT/SELECT only, UPDATE/DELETE revoked) arrives in Phase 6
-- where the audit_events table exists. In dev the app currently connects as the RDS master user.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS request;
CREATE SCHEMA IF NOT EXISTS onboarding;
CREATE SCHEMA IF NOT EXISTS registry;
CREATE SCHEMA IF NOT EXISTS access;
CREATE SCHEMA IF NOT EXISTS directory;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS notify;
