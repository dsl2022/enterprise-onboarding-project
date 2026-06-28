-- Phase 2 baseline: the persistence substrate every later v1 module builds on.
--
-- pgvector is enabled here (cheap; the assistant's embedding TABLES land with the assistant in a later
-- phase). One schema per owning module enforces the "each module owns its tables, no cross-module
-- table access" boundary at the database level — TABLES are added in each module's own phase, not now.
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
CREATE SCHEMA IF NOT EXISTS assistant;
