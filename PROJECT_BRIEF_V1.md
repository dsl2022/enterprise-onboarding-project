# Master Build Prompt — v1 Backend (Portal domain, contract-first, CI-driven)

Source of truth for v1. Builds the backend, API contract, data layer, infra, and real provisioning
for the full portal **on top of the proven v0 skeleton**. The production Angular UI is a separate
prompt built later against the frozen contract — not built here. All Terraform runs in CI.

## Resolved decisions (Phase 0, 2026-06-28)
- **Assistant module:** STUB for now (endpoints return not-implemented). Built fully later as a
  **separate track** with its own discussion. pgvector embeddings/tables deferred with it.
- **Blue/green (CodeDeploy):** built as the **final, skippable phase**; everything else ships on the
  v0 rolling deploy first.
- **RBAC role source:** Entra **group-membership claim** in the ID token; map group GUID → role.
- **Dev data tier:** smallest **single-AZ** (db.t4g.micro, cache.t4g.micro single node, ≥2 small
  tasks); Multi-AZ behind a variable flag for later.
- **Defaults:** Flyway migrations; transactional **outbox in Postgres** for events (not SQS);
  hand-authored OpenAPI + contract tests; `azuread_group` manages the new groups (needs
  `Group.ReadWrite.All` + consent on the CI app); roles scoped to the v1 service set (not per-ARN).

## v0 reality-checks folded in
- No DB / migration tool / `notify` stub existed in v0 — all net-new.
- `platform` is overloaded: TF `modules/platform` (KMS/ECR/logs) vs new Java `com.eop.platform`.
- New consents land on the **workload** app `eop-dev-app` (currently only `Group.Read.All`).

---

## 0. Role & operating rules
1. Ground in v0 + `docs/AS-BUILT.md`; reuse conventions exactly (`com.eop.<concern>` four-file layout,
   single TF state, SSM image-tag deploy spine, plan/apply/destroy workflows with the `dev` approval
   gate, CloudFront WIF issuer ADR-0007, `azuread`/`aws` providers).
2. **Contract-first.** Phase 1 freezes the OpenAPI contract + role→permission matrix + the two request
   state machines. Nothing else starts until the contract is frozen.
3. CI-driven, never local. PR → plan → merge behind `dev` approval. `gh` for PRs/monitoring.
4. No hardcoded secrets, no static cloud creds; OIDC federation both clouds. Secrets in Secrets
   Manager, config in SSM.
5. Apply v0 hardening (ADR-0009): SHA-pin Actions; scope apply/deploy roles down from Admin.
6. Maintain `DECISIONS.md` + `RUNBOOK.md`.
7. Cost-optimized single region; keep the manual destroy workflow.

## 1. What v1 builds
A generic **request → approve → provision → audit → notify** engine with two request types sharing one
workflow/lifecycle/approval/SoD rule: **application onboarding** (creates a real Entra app
registration) and **access request** (adds the user to a mapped Entra group via Graph). Plus per-role
RBAC, super-admin audited impersonation, immutable audit trail, email + in-app notifications.
Not here: the production Angular UI.

## 2. Modules (`com.eop.<concern>`, four-file convention)
platform (filter chain; Redis-backed BFF session; JWKS; correlation IDs; RFC-7807; v0 WIF issuer with
pre-provisioned key) · auth (v0 BFF + extended `/me`) · authz (6 roles from group claim; permission
checks at service layer; ABAC ownership; separation of duties; impersonation) · request (shared
`Request` aggregate + state machine + domain events) · onboarding · registry (secrets as references) ·
access (catalog, requests, my-access, removal) · directory (Graph read + writes: app reg, group
member add/remove; pagination/429/403) · audit (append-only, hash-chained; impersonated actions under
Super Admin) · notify (SES + in-app feed; event-driven, async, retry + dead-letter, idempotent) ·
assistant (RAG + wizard tools; **stubbed for now**).

## 3. Roles (bundles of permissions, from Entra group membership)
APPLICATION_OWNER (App-Owners) · SSO_OPERATIONS (SSO-Operations) · ADMIN (Admins) · AUDITOR (Auditors)
· READ_ONLY (Read-Only) · SUPER_ADMIN (Super-Admins, god mode + audited impersonation). Seed the
missing groups + assign test users. Impersonation: Super Admin only; effective role swaps but identity
stays Super Admin and every action is audited under Super Admin. Enforce permission + ownership + SoD
at the service layer.

## 4. API contract (frozen in Phase 1 — see docs/api/openapi-v1.yaml)
Versioned REST; every mutation emits a domain event; POSTs take `Idempotency-Key`; lists
cursor-paginated; PATCH uses ETag/If-Match. Endpoints: identity/impersonation, applications
(onboarding+registry), catalog+access, unified review-queue, teams+members, audit, notifications,
assistant. State machines:
- Onboarding: DRAFT → SUBMITTED → UNDER_REVIEW → (CHANGES_REQUESTED⤴ | REJECTED | APPROVED →
  PROVISIONING → ACTIVE)
- Access: SUBMITTED → UNDER_REVIEW → (CHANGES_REQUESTED⤴ | REJECTED | APPROVED → PROVISIONING →
  GRANTED). Provisioning is async in both.

## 5. Provisioning via Graph
App onboarding → create Entra app registration (`Application.ReadWrite.OwnedBy`), returns client ID.
Access → add member to mapped group (`GroupMember.ReadWrite.All`); removal removes. New admin consents
(human/GA) on the workload app: `GroupMember.ReadWrite.All`, `Application.ReadWrite.OwnedBy` (keep
`Group.Read.All`). Reuse v0 WIF — no new stored credential; honor issuer exact-match gotchas.
`simulate_provisioning` flag may stub when consent isn't granted; default real.

## 6. Notifications (Option A, event-driven)
Recipient rules by role/relationship (no per-user prefs): submitted → approvers + requester; changes
→ requester; approved → requester (+team); rejected → requester; provisioned/active/granted →
requester (+team); removal requested → approvers; removed → requester. SES email (sandbox ok;
production-access a RUNBOOK step) + in-app feed. Idempotent, async, retry + dead-letter.

## 7. Data layer
PostgreSQL + pgvector; each module owns its schema/tables; no cross-module table access; Flyway
migrations. Tables: applications, requests, onboarding/access rows, catalog_resources, teams,
team_members, audit_events (hash-chained), notifications, (assistant_docs+embeddings deferred).

## 8. HA upgrade
≥2 Fargate tasks; BFF session → ElastiCache Redis (Spring Session); pre-provision the WIF issuer
signing key in Secrets Manager (Terraform, once — removes the single-task race). Graph token cache may
stay per-task. RDS Multi-AZ behind a flag.

## 9. Blue/green (final phase)
ECS `deploymentController=CODE_DEPLOY`; two target groups + test listener; appspec.yaml; canary/linear
shift; auto-rollback on a CloudWatch alarm (login-success / 5xx / 401). Own module. Minimal shape.

## 10. Phased plan
0 read+map+plan → 1 freeze contract → 2 data model+migrations → 3 request engine + authz → 4
onboarding+registry+app-reg provisioning → 5 access+group-add provisioning → 6 audit+notify → 7
assistant (**stub**) → 8 HA (multi-task, Redis, pre-provisioned key) → 9 blue/green → 10 hardening+docs.
Tests each phase: unit, ArchUnit, Testcontainers, contract tests, SoD rule.

## 11. Definition of done
Real per-role logins; Super Admin impersonation (audited, banner); onboarding → real Entra app+client
ID → active with audit+email+bell; access → real Graph group-add → granted in My Access with
notifications; removal works; SoD enforced; ≥2 tasks + Redis sessions; blue/green canary +
auto-rollback; clean `terraform destroy`.
