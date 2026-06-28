# CR-20260628-1416 — v1 Phase 2 data-layer prod-hardening (deferred items)

- **ID:** CR-20260628-1416
- **Date:** 2026-06-28 14:16 EDT
- **Author:** Consolidated review — senior-architect (Claude) + retired v0/v1 build agent
- **Target:** PR #13 — `v1-phase2-data` ("v1 Phase 2: data layer")
- **Status:** Open (tracked — deferred to the phase noted per item; NOT blockers for the Phase 2 dev merge)
- **Related:** deploy/terraform/modules/{data,cache,service} · app/src/main/resources/application-data.yml · DECISIONS.md (ADR-0012)

## Context

Two independent reviews of PR #13 (Phase 2 data layer) agreed it is **merge-ready for the dev
milestone** — CI green, frozen contract untouched, v0 conventions respected, secret/CMK handling
correct. Small fixes were applied directly in PR #13 (see "Applied" below). The items here are
**intentionally NOT done in Phase 2** (dev data is disposable; `infra-destroy` between sessions),
captured so they survive past the build chat and land in the right later phase.

## Tracked items (deferred — each with a target)

### 1. RDS master-password rotation vs. static credential injection → Phase 6 / prod
`modules/data` uses `manage_master_user_password = true` → RDS-managed automatic rotation (~7-day
default). ECS injects `DB_USERNAME`/`DB_PASSWORD` **only at task start**, so after a rotation a running
task fails on its next *new* pooled connection (Hikari `maxLifetime` recycle, ~30 min — not just on
restart). Harmless in dev (sessions < 7 days, disposable). **Prod fix (with item 2):** a dedicated
non-superuser app DB role with app-managed credentials or **IAM database authentication**, or have the
app re-read the secret on rotation. *(senior-architect)*

### 2. Restricted DB roles; stop using the RDS master/superuser → Phase 6
The dev app connects as the RDS master (`eopadmin`, `rds_superuser`). Phase 6 must add the **locked-down
audit role** (`INSERT`/`SELECT` on `audit_events`; `UPDATE`/`DELETE` revoked) when that table lands —
and, more broadly, a **least-privilege app role** instead of the master user (ties into item 1).
*(both reviewers)*

### 3. Verify `CREATE EXTENSION vector` on real RDS PG16 → Phase 2 first apply
pgvector is proven only on the upstream `pgvector/pgvector:pg16` Testcontainers image. RDS PG16 supports
it, but confirm the extension creates on the **first `infra` apply** (check the Flyway/app boot log). No
code change expected. *(senior-architect)*

### 4. Redis transit encryption + AUTH → prod
`modules/cache` has `transit_encryption_enabled = false` (dev: SG-isolated; session/auth data plaintext
in-VPC). For prod: enable it, move clients to `rediss://`, add an AUTH token. *(senior-architect)*

### 5. Spring Session serialization: JDK → JSON for cross-version safety → prod
ADR-0012 keeps default JDK serialization (proven by the Redis round-trip test). Residual risk: a Spring
Security class change between deploys can make **old Redis sessions undeserializable → forced
re-logins**. Revisit JSON serialization before prod. *(senior-architect)*

## Applied directly in PR #13 (context, not tracked here)
- Redis `at_rest_encryption` pinned to the **project CMK** (consistency with RDS storage/secret + logs).
- ADR-0012 Consequences line added for item 1 (rotation-vs-injection gap).
- *(optional)* `assistant` schema dropped from `V1__baseline.sql` until its own track.

## Do-not-change (validated correct)
`flyway-database-postgresql` present (required on Boot 3.3 / Flyway 10); CMK-pinned RDS master secret +
`:json-key::` injection; ALB check on dependency-free `/healthz` (DB/Redis in readiness only); single-flag
`multi_az` HA toggle; CI `test` job split from the image build; ArchUnit boundary scaffold; `ddl-auto:
validate` with Flyway owning schema.

## Resolution

**"Applied directly in PR #13" — done** (this follow-up commit on `v1-phase2-data`):
- Redis `at_rest_encryption` pinned to the project CMK (`modules/cache`: `kms_key_id = var.kms_key_arn`,
  new `kms_key_arn` var wired from `module.platform`).
- ADR-0012 gains the rotation-vs-injection Consequences line (item 1) + the Redis-CMK + 7-schema notes.
- `assistant` schema dropped from `V1__baseline.sql` (and `DataLayerTest` now asserts it is absent).

**Item 3 — RESOLVED at first apply (2026-06-28).** PR #13 merged + applied; the app booted and login
worked, which requires Flyway `V1__baseline.sql` to have applied successfully on real RDS PG16 (a failed
migration aborts startup) — so `CREATE EXTENSION vector` + the 7 module schemas are confirmed on RDS.
Live restart of the ECS tasks + same-tab refresh stayed authenticated, proving the Redis-backed BFF
session survives task lifecycle.

**Deferred items remain Open**, each resolving in its noted phase: items 1–2 at Phase 6
(least-privilege/locked-down DB roles); items 4–5 at prod hardening / Phase 10 (Redis transit
encryption + AUTH; JSON session serialization).
