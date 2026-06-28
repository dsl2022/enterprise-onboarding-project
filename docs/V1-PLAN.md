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
| `azuread` provider (ADR-0003), workload app `eop-dev-app` | add group claim, seed groups, new app-only consents (`GroupMember.ReadWrite.All`, `Application.ReadWrite.OwnedBy`) |
| `auth` BFF (Spring Security oauth2Login), in-memory session | session → **Redis** (Spring Session); `/me` extended with role/group/impersonation |
| RFC-7807 / correlation IDs | formalized in `platform` |

**Naming:** keep the `eop`/`eop-dev` prefix; new SSM under `/eop/<env>/<name>`; new secrets
`eop-<env>/<name>`; group GUID→role map sourced from `module.entra` outputs.

## Revised phased checklist (each phase = PR → plan → `dev` approve → apply)
- [ ] **Phase 1 — Freeze the contract** (this PR): OpenAPI + RBAC matrix + state machines + plan. *Review & freeze.*
- [ ] **Phase 2 — Data layer:** TF `data` module (RDS Postgres single-AZ + pgvector ext, Multi-AZ flag) + `cache` module (Redis single node); Flyway baseline; per-module schemas (no cross-module access). pgvector enabled; assistant tables deferred.
- [ ] **Phase 3 — `request` engine + `authz`:** shared `Request` aggregate + state machine + transition guards + domain events (transactional outbox); 6 roles from the **group claim**, permission checks, ABAC ownership, **separation of duties**, impersonation. Enable group claim on the Entra app; seed groups + test users.
- [ ] **Phase 4 — `onboarding` + `registry` + app-reg provisioning** via `directory` (`Application.ReadWrite.OwnedBy`); async provisioning → client ID; secrets-as-references.
- [ ] **Phase 5 — `access`** (catalog, requests, my-access, removal) + group-add provisioning (`GroupMember.ReadWrite.All`).
- [ ] **Phase 6 — `audit`** (hash-chained) + **`notify`** (SES email + in-app feed; outbox-driven, idempotent, retry + dead-letter).
- [ ] **Phase 7 — `assistant` STUB** (endpoints return 501; full RAG/tools = separate track).
- [ ] **Phase 8 — HA:** ≥2 tasks; Redis-backed session; **pre-provision** the WIF issuer key.
- [ ] **Phase 9 — Blue/green** (CodeDeploy canary + alarm rollback; own module). *Skippable.*
- [ ] **Phase 10 — Hardening:** SHA-pin Actions; scope apply/deploy roles to the v1 service set; docs + new consents.

Tests each phase: unit, **ArchUnit** (module boundaries), **Testcontainers** (Postgres/Redis), **contract tests** (against the frozen OpenAPI, across roles), and the **SoD** rule.

## Assumptions (correct me at freeze)
1. **Flyway** migrations; **transactional outbox in Postgres** for events (no SQS) — matches the "dead-letter table" wording, no new queue infra.
2. **Hand-authored OpenAPI** + contract tests (not server codegen).
3. **`azuread_group`** manages Admins/Read-Only/Super-Admins → needs `Group.ReadWrite.All` + consent on the **CI** app (one-time). Else you create them and give me the GUIDs.
4. **Group claim** = `SecurityGroup`, emitted as group **GUIDs**; authz maps GUID→role from `module.entra` outputs. (Overage >200 groups → out of scope for this tenant.)
5. Dev sizing: `db.t4g.micro` single-AZ, `cache.t4g.micro` single node, 2× `cpu=256/mem=512` tasks; Multi-AZ behind a flag.
6. Roles scoped to the v1 **service set** (ECS/RDS/ElastiCache/SES/SecretsManager/SSM/CodeDeploy/ELB/CloudWatch), not per-ARN.
7. RDS dev: `skip_final_snapshot=true`, `deletion_protection=false` so `infra-destroy` stays clean (data is disposable in dev).
8. `simulate_provisioning` defaults **real** once consents are granted; falls back to stub if not.

## What I need from you (for later phases — not blocking the freeze)
- **SES**: a sender email you can verify + 1–2 recipient addresses (sandbox).
- **Consent** (you, GA) at Phases 4–5: `Application.ReadWrite.OwnedBy` + `GroupMember.ReadWrite.All` on `eop-dev-app` (commands emitted to RUNBOOK); and `Group.ReadWrite.All` on the CI app if Terraform manages groups.
- **Test users** to assign to each seeded group so real per-role logins work.
