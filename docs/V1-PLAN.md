# v1 Plan — mapping onto v0, phased checklist, assumptions

Companion to [PROJECT_BRIEF_V1.md](../PROJECT_BRIEF_V1.md). Grounded in the real v0
([docs/AS-BUILT.md](AS-BUILT.md)). **Contract freeze (Phase 1) is the gate — no module code until the
[OpenAPI](api/openapi-v1.yaml) + [RBAC matrix](api/rbac-matrix.md) are approved.**

## How v1 maps onto v0 (reuse, don't fight)
| v0 convention | v1 usage |
|---|---|
| `com.eop.<concern>` four-file Java layout | new concerns: `authz`, `request`, `onboarding`, `registry`, `access`, `directory`, `audit`, `notify`, `assistant`(stub), `platform`(Java) |
| TF `modules/<name>` (4 files), single state, root `main.tf` wiring | new modules: `data` (RDS+pgvector), `cache` (Redis), `messaging`? (none — outbox in DB), `notify` (SES), `bluegreen` (Phase 9); extend `service` (≥2 tasks, Redis env, new task-role grants), `entra` (group claim + new groups + consents) |
| SSM image-pointer + gated `infra` apply deploy spine | unchanged through Phase 8; Phase 9 swaps the app service to CodeDeploy blue/green (own module) |
| WIF issuer (CloudFront, ADR-0007), app generates key on boot | key **pre-provisioned** in Secrets Manager (Phase 8) — removes the single-task race AS-BUILT §5 flagged |
| `azuread` provider (ADR-0003), workload app `eop-dev-app` | declare **6 app roles** (RBAC) + assign test users; keep **access-governance groups**; new app-only consents (`GroupMember.ReadWrite.All`, `Application.ReadWrite.OwnedBy`) |
| `auth` BFF (Spring Security oauth2Login), in-memory session | session → **Redis** (Spring Session); `/me` extended with role/group/impersonation |
| RFC-7807 / correlation IDs | formalized in `platform` |

**Naming:** keep the `eop`/`eop-dev` prefix; new SSM under `/eop/<env>/<name>`; new secrets
`eop-<env>/<name>`; group GUID→role map sourced from `module.entra` outputs.

## Revised phased checklist (each phase = PR → plan → `dev` approve → apply)
- [ ] **Phase 1 — Freeze the contract** (this PR): OpenAPI + RBAC matrix + state machines + plan. *Review & freeze.*
- [ ] **Phase 2 — Data layer:** TF `data` module (RDS Postgres single-AZ + pgvector ext, Multi-AZ flag) + `cache` module (Redis single node); Flyway baseline; per-module schemas (no cross-module access). pgvector enabled; assistant tables deferred.
- [ ] **Phase 3 — `request` engine + `authz`** (split for review): 6 roles from the **app-roles (`roles`) claim** with most-privileged precedence, permission checks, ABAC ownership, **separation of duties**, impersonation (permissions from impersonated role; identity/SoD/ABAC/audit from the **real** principal); shared `Request` aggregate + state machine + transition guards + domain events (transactional outbox in a shared `messaging` schema). Declare app roles on the Entra app + assign test users.
  - **3a** (`authz` + identity + Entra app-roles): the RBAC spine — `platform`/`authz`/`auth`, `/me` + `/impersonation`, 6 app roles + `app_role_assignment_required`. No DB tables. *(ADR-0013.)*
  - **3b** (`request` engine): `Request` aggregate + state machine + guarded transition serializer + outbox (write-only), `V2` migration.
- [ ] **Phase 4 — `onboarding` + `registry` + app-reg provisioning** via `directory` (`Application.ReadWrite.OwnedBy`); async provisioning → client ID; secrets-as-references.
- [ ] **Phase 5 — `access`** (catalog, requests, my-access, removal) + group-add provisioning (`GroupMember.ReadWrite.All`).
- [ ] **Phase 6 — `audit`** (hash-chained, **single-writer** via the outbox relay + advisory lock; DB role `INSERT`/`SELECT` only, `UPDATE`/`DELETE` revoked; `GET /audit/verify`) + **`notify`** (SES email + in-app feed; outbox relay with `FOR UPDATE SKIP LOCKED`/leader election; idempotent, retry + dead-letter).
- [ ] **Phase 7 — `assistant` STUB** (endpoints return 501; full RAG/tools = separate track).
- [ ] **Phase 8 — HA:** ≥2 tasks; Redis-backed session; **pre-provision** the WIF issuer key. (Dev Redis is **single-node** — a session SPOF, *not* HA; the flag enables a replica/multi-AZ for prod.)
- [ ] **Phase 9 — Blue/green** (CodeDeploy canary + alarm rollback; own module). *Skippable.* Note: flipping the ECS service to `deploymentController=CODE_DEPLOY` is a **destructive cutover** (recreates the service, replaces the rolling SSM spine) — the module owns the cutover and the RUNBOOK documents it.
- [ ] **Phase 10 — Hardening:** SHA-pin Actions; scope apply/deploy roles to the v1 service set; docs + new consents.

Tests each phase: unit, **ArchUnit** (module boundaries), **Testcontainers** (Postgres/Redis), **contract tests** (against the frozen OpenAPI, across roles), and the **SoD** rule.

## Assumptions (correct me at freeze)
1. **Flyway** migrations; **transactional outbox in Postgres** for events (no SQS) — matches the "dead-letter table" wording, no new queue infra. Outbox relay claims rows with `FOR UPDATE SKIP LOCKED` (or leader election) so ≥2 tasks don't double-dispatch; `notify` is idempotent for the residual.
2. **Hand-authored OpenAPI** + contract tests (not server codegen) + a **Spectral** lint gate in CI.
3. **RBAC = Entra app roles** (`roles` claim), declared on the workload app via `azuread_application.app_role` + assigned to test users (`azuread_app_role_assignment`). The old role-*groups* are dropped; **access-governance groups** (e.g. `aws-prod-engineers`) remain and may be `azuread_group`-managed (needs `Group.ReadWrite.All` + consent on the **CI** app, or you create them).
4. **Audit** is single-writer (outbox relay + Postgres advisory lock) with DB-level immutability (app role `INSERT`/`SELECT` only; `UPDATE`/`DELETE` revoked) and a `GET /audit/verify` chain check.
5. **Idempotency** (per-principal+endpoint, 24h): replay→original, key+different-body→422, business conflict→409. **Optimistic concurrency** via `If-Match`/`ETag` (412) on `submit`/`decision`/`patch`, layered over the authoritative DB transition guard.
6. Dev sizing: `db.t4g.micro` single-AZ, `cache.t4g.micro` **single node (session SPOF, not HA)**, 2× `cpu=256/mem=512` tasks; Multi-AZ/replica behind a flag.
7. Roles scoped to the v1 **service set** (ECS/RDS/ElastiCache/SES/SecretsManager/SSM/CodeDeploy/ELB/CloudWatch), not per-ARN.
8. RDS dev: `skip_final_snapshot=true`, `deletion_protection=false` so `infra-destroy` stays clean (data is disposable in dev).
9. `simulate_provisioning` defaults **real** once consents are granted; falls back to stub if not.

## What I need from you (for later phases — not blocking the freeze)
- **SES**: a sender email you can verify + 1–2 recipient addresses (sandbox).
- **Consent** (you, GA) at Phases 4–5: `Application.ReadWrite.OwnedBy` + `GroupMember.ReadWrite.All` on `eop-dev-app` (commands emitted to RUNBOOK); and `Group.ReadWrite.All` on the CI app if Terraform manages groups.
- **Test users** to assign to each **app role** (one per role) so real per-role logins work.
