# Architecture Decision Records

Short ADRs. Newest decisions appended. Status: Accepted unless noted.

## ADR-0001 — Reuse the godaddy CI/CD pattern verbatim
**Context:** `/Users/dmml/interview-2026/godaddy` already has a working, OIDC-federated, zero-stored-credential
Terraform + GitHub Actions setup (S3+DynamoDB remote state, partial backend config, three GitHub→AWS
OIDC roles keyed on `sub` per trigger, `infra.yml`/`infra-destroy.yml`/`app-deploy.yml`/`ci.yml`,
env-gated applies, `modules/{network,platform,app}`, `bootstrap/`).
**Decision:** Reuse the layout, workflow shapes, backend strategy, OIDC role/sub patterns, naming
(`<project>-<env>` prefix, `Project/Env/ManagedBy` default tags) unchanged. Change only the project
prefix to `eop` and extend with the Azure side.
**Consequences:** Fast, consistent, low-risk. Drop godaddy's `audit_trail` module and Go/Lambda/SQS
pieces (out of scope for v0).

## ADR-0002 — All Terraform runs in CI; no local apply
**Decision:** `terraform apply` only runs in GitHub Actions, gated by the `dev` GitHub Environment
(required reviewer). Plans run on PR and post as a comment. No "ask in chat before apply" — the PR +
environment review is the guardrail. Local use is limited to `fmt`/`validate` (`init -backend=false`).

## ADR-0003 — Azure is config-only → `azuread` provider only, no `azurerm`/subscription
**Context:** v0 deploys **no** Azure compute. The only Azure objects are an Entra app registration, a
federated identity credential, and Graph app permission — all directory objects.
**Decision:** Use only the Terraform `azuread` provider. Do **not** add `azurerm` or require an Azure
subscription id. The CI Entra app (`eop-github-ci`) is granted directory permission to manage app
registrations (`Application.ReadWrite.OwnedBy`) and uses `azure/login` with `allow-no-subscriptions: true`.
**Consequences:** Removes an entire credential/permission surface (no Contributor-on-subscription). If
we later deploy Azure resources, revisit and add `azurerm` + subscription.

## ADR-0004 — Call Microsoft Graph via raw REST (`RestClient`), not the Graph Java SDK
**Context:** The WIF token comes from our **self-hosted** OIDC issuer via a custom client-assertion
flow. The Graph Java SDK's auth model assumes an `azure-identity TokenCredential`.
**Decision:** Use Spring's `RestClient` for both the Entra token exchange and the single Graph call.
**Consequences:** Less code than adapting the SDK; `@odata.nextLink` paging and `429`/`Retry-After` are
explicit and legible (supports the learning checkpoints); smaller image, fewer deps. Revisit if Graph
surface grows.

## ADR-0005 — One Terraform state file; order issuer before Azure federated credential
**Context:** The Azure federated identity credential needs the CloudFront issuer URL, which AWS
produces. godaddy uses a single state file.
**Decision:** Keep a single state file. Wire the CloudFront issuer module output into the
`azuread_application_federated_identity_credential` via module reference (no second state, no manual
URL copy-paste). Issuer (Phase 2) is created before the Azure config (Phase 3).

## ADR-0006 — Single Entra app registration for both flows in v0
**Decision:** One app registration carries both the Flow 1 web/redirect config (delegated scopes +
client secret in AWS Secrets Manager) and the Flow 2 federated identity credential + `Group.Read.All`
application permission. Document that it could be split into two registrations later.

## ADR-0007 — CloudFront default domain as the issuer host
**Decision:** Use the CloudFront distribution's default `*.cloudfront.net` domain as both the issuer
host and the app entry point for v0 (zero cost, zero DNS, stable HTTPS, Entra-trustable). The
discovery doc's `issuer` field is set to exactly `https://<distribution-domain>`. Revisit with a real
domain + ACM if a custom hostname is needed.

## ADR-0008 — CI builds the image via Docker; no local Maven required
**Context:** No Maven installed locally; authoritative image build happens in CI anyway.
**Decision:** The Dockerfile uses a `maven:3.9-eclipse-temurin-21` build stage so `docker build` is the
only thing needed to produce the jar (locally and in CI). No Maven/Gradle wrapper is committed.
**Consequences:** `ci.yml` builds via `docker build` (also Trivy-scannable); developers without Maven
use Docker. Revisit if we want fast non-Docker unit-test runs in CI (add `setup-java` + a wrapper then).

## ADR-0009 (TODO/hardening) — Pin GitHub Actions to commit SHAs
**Context:** godaddy pins all third-party actions to commit SHAs (supply-chain). This scaffold pins to
version tags (e.g. `@v4`) for the first PR because SHAs can't be resolved offline.
**Decision (deferred):** Before merging to `main` for real use, replace tag pins with commit SHAs.
Tracked as a hardening task.

## ADR-0011 — CI Entra identity needs Application.ReadWrite.All (not OwnedBy)
**Context:** Phase 3's first apply failed: `eop-github-ci` (granted `Application.ReadWrite.OwnedBy`)
could create the workload `azuread_application` but got `403 Authorization_RequestDenied` creating its
**service principal** and **federated identity credential**. Per Microsoft Graph, creating a service
principal requires `Application.ReadWrite.All`; `OwnedBy` is insufficient.
**Decision:** Upgrade the CI app's Graph permission to **`Application.ReadWrite.All`**
(`1bfefb4e-…`, admin-consented) in `deploy/terraform/bootstrap-azure`. Re-apply the bootstrap-azure
layer (run by a Global Admin via `az login`), then re-run the Phase 3 apply.
**Consequences:** The CI identity can now manage all app registrations/SPs in the tenant — broader than
OwnedBy, but the standard permission for a Terraform-manages-Entra CI identity. It still **cannot grant
admin consent** for the workload app's `Group.Read.All` (that remains the human Global-Admin step,
RUNBOOK §4). Auth stays OIDC-federated — no client secret.

## ADR-0010 — Issuer signing key lives only in Secrets Manager; the app owns it
**Context:** The workload OIDC issuer needs an RSA keypair: the private key signs assertions, the public
key is published as JWKS. Terraform can't compute the JWK modulus/exponent (`n`/`e`) in pure HCL, and
generating the key in Terraform (`tls_private_key`) would persist the private key in remote state.
**Decision:** Terraform provisions only the *hosting* (private S3 + CloudFront/OAC), the *discovery
document* (deterministic — it only needs the CloudFront domain for `issuer`/`jwks_uri`), an **empty**
Secrets Manager secret (CMK-encrypted), and the task IAM policy. **The app generates the RSA key on
first boot** (Nimbus), stores the private JWK in Secrets Manager, and publishes `.well-known/jwks.json`
to the bucket. The `kid` is the RFC-7638 thumbprint so JWKS and the assertion header always agree.
**Consequences:** The private key never enters Terraform state or the repo. JWKS goes live at first app
boot (Phase 4) — acceptable because Entra only fetches it at the Phase 5 exchange. Phase 2 verification
is limited to the discovery doc over HTTPS. Issuer beans are gated by `wif.enabled` (off in Phase
1/local/CI), so the app boots with no AWS dependency until Phase 4. CloudFront uses Managed-
CachingDisabled so a rotated key's JWKS propagates immediately.

## ADR-0012 — Phase 2 data layer: RDS-managed master secret on the CMK; pgvector via Flyway; Redis-backed BFF session
**Context:** v1 needs persistence (Postgres, with pgvector for the later assistant) and a session store
that lets Fargate run ≥2 stateless tasks (Phase 8). The v0 spine (single TF state, SSM image pointer,
gated `infra` apply, CMK, private subnets, task SG with open egress) must be reused, not forked.
**Decision:**
- **`modules/data` (RDS Postgres 16, `db.t4g.micro`, single-AZ; `multi_az` flag for prod).** The master
  password is **RDS-managed** (`manage_master_user_password = true`) so it never enters TF state — and it
  is pinned to the **project CMK** (`master_user_secret_kms_key_id = kms_key_arn`), *not* the
  `aws/secretsmanager` default key, so the ECS **execution role's existing `kms:Decrypt` grant** on that
  CMK covers secret injection. The task pulls `username`/`password` from the secret JSON via ECS's
  `:json-key::` suffix; only non-secret coordinates (`DB_HOST/PORT/NAME`) are plain env.
- **pgvector is enabled by a Flyway migration** (`CREATE EXTENSION vector`), not an RDS parameter group —
  app-owned, and works identically on the `pgvector/pgvector:pg16` test image (both have superuser/
  `rds_superuser`). `V1__baseline.sql` creates **one schema per owning module** (no tables yet) to
  enforce the "no cross-module table access" boundary at the DB level — **7 schemas; `assistant` is
  omitted** until its deferred track lands its own migration (CR-1416).
- **`modules/cache` (ElastiCache Redis, `cache.t4g.micro`).** Default is a **single node — a session
  SPOF, not HA** (labeled honestly in code + RUNBOOK); `multi_az` toggles `num_cache_clusters`,
  `automatic_failover_enabled`, and `multi_az_enabled` together. At-rest encryption is pinned to the
  **project CMK** (consistency with RDS storage/secret + the log group; ElastiCache grants on it via the
  apply principal, allowed by the CMK's root `kms:*` IAM delegation). Spring Session stores the BFF
  session here. **Default JDK serialization was proven** (Testcontainers round-trip: an OIDC
  `SecurityContext` with principal + authorities survives a Redis save/reload intact), so no
  `SecurityJackson2Modules` serializer is needed; revisit only if that test ever fails (CR-1416 item 5).
- **Boot-race handling:** the `service` references the RDS endpoint, so TF orders DB creation (and its
  ~10-min `available` wait) *before* tasks roll; belt-and-suspenders, the app retries the Flyway/JDBC
  connection on boot and the ECS health-check grace is raised to 300s. The ALB target-group check stays
  on the dependency-free `/healthz`; DB/Redis appear only in actuator **readiness** for observability.
**Consequences:** No new secret material in state or repo; teardown stays clean (`skip_final_snapshot`,
`deletion_protection=false`, managed secret deletes with the instance, Redis no final snapshot). Adds
~a few $/day when applied → `infra-destroy` between sessions. The dev app still connects as the RDS
master user; the locked-down audit DB role (INSERT/SELECT only) is deferred to Phase 6 where the
`audit_events` table exists. Tests run in a dedicated CI `test` job (`mvn -B verify`, Testcontainers on
the runner's Docker); the image-build job keeps `-DskipTests`.
**Known gap (CR-1416, deferred to Phase 6 / prod):** the RDS-managed master password auto-rotates
(~7-day default), but ECS injects `DB_USERNAME`/`DB_PASSWORD` only at task start — so post-rotation a
long-lived task fails on its next *new* pooled connection (Hikari `maxLifetime` recycle, ~30 min), not
just on restart. Harmless in dev (sessions < 7 days, disposable); the prod fix is a least-privilege,
app-managed app role (or IAM DB auth, or re-reading the secret on rotation), tracked with the
master-user/locked-down-role work in Phase 6.

## ADR-0013 — Phase 3a: portal RBAC (app-roles union) + identity/impersonation
**Context:** v1 needs real per-role authorization from Entra, separation of duties, and audited
impersonation — built against the frozen `/me` + `/impersonation` contract and the RBAC matrix. The
request *engine* is a separate, larger unit (3b); 3a isolates the RBAC spine and the login-affecting
Entra change so a forgotten assignment can't be entangled with engine logic.
**Decision:**
- **RBAC from the app-roles (`roles`) claim, not groups.** Six `PortalRole`s; the frozen matrix is
  transcribed as data (`RolePermissions`). Authorization is a **permission** check at the service layer
  via a programmatic `AuthorizationService.require(principal, permission[, resource])` — chosen over
  `@PreAuthorize` because ABAC ownership + SoD need the loaded resource. **Union** of all effective
  roles; **most-permissive scope** (`ALL` beats `OWN`; `OWN` only if every granting role is `OWN`) per
  CR-1056. `/me.role` is display-only (most-privileged), never an auth input.
- **Impersonation** overlay lives in the **Redis-backed session** (Phase 2 reuse). The `impersonate`
  permission is checked against the **real** roles (not the effective overlay), so an active
  impersonation can't self-escalate. While impersonating: **permissions** come from the impersonated
  role; **identity/SoD/ABAC/audit** stay the real principal (the laundering guard — proven by test:
  submit-as-self → impersonate ops → approve is blocked). `/me.roles` returns the **effective** set (not
  real) so the UI gates to the reduced view; real identity is conveyed by `isSuperAdmin` +
  `impersonating.role`. `/me.group` is null in v1 (groups are access governance, read later).
- **Entra:** the 6 app roles are declared on `eop-dev-app` with **fixed UUID ids** (ours to declare —
  unlike Graph GUIDs which are resolved live; a changed id orphans assignments), assigned to **users**.
  `app_role_assignment_required` defaults **false**; it is flipped true only AFTER every interactive
  login holds a role (else sign-in breaks). Flow-2 WIF is app-only and unaffected. The app also handles
  the zero-roles edge defensively (empty union, `READ_ONLY` display floor).
- **Outbox → shared `messaging` schema (not `request`)** so Phase 4/5/6 emitters and the Phase 6 relay
  don't cross-access the `request` schema (preserving the ArchUnit boundary); written via a shared
  `platform` `OutboxWriter`. *Decided here; implemented in 3b where the engine + outbox land.*
**Consequences:** No DB tables in 3a (impersonation is session state) → no migration; additive Entra
(6 roles + assignments). Module graph stays acyclic (`platform`/`auth`/`request` → `authz`; never the
reverse) — enforced by ArchUnit. RFC-7807 + correlation ids formalized in `platform`. Real per-role
logins + impersonation demo end-to-end once the app-role assignments are applied (RUNBOOK §8).
