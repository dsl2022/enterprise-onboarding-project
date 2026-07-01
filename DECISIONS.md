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

## ADR-0009 — Pin GitHub Actions to commit SHAs
**Status:** Accepted — **implemented in Phase 10-2** (was deferred/TODO through v1 build). Closes #47.
**Context:** godaddy pins all third-party actions to commit SHAs (supply-chain). The scaffold pinned to
version tags (e.g. `@v4`) through the v1 build because SHAs couldn't be resolved offline. A mutable tag is a
supply-chain hole: a compromised or re-pointed `@v4` runs attacker code with our OIDC-federated AWS/Entra
creds (the workflows assume the DEPLOY/APPLY roles). 
**Decision (implemented):** All third-party `uses:` across the five workflows (`ci`, `infra`,
`app-deploy`, `frontend-deploy`, `infra-destroy`) are pinned to **full 40-char commit SHAs** with a trailing
`# vX.Y.Z` comment for readability — 8 unique actions (`actions/checkout|setup-java|setup-node|github-script`,
`aws-actions/configure-aws-credentials|amazon-ecr-login`, `hashicorp/setup-terraform`,
`browser-actions/setup-chrome`). A `.github/dependabot.yml` `github-actions` ecosystem (weekly) keeps the pins
current — Dependabot bumps the SHA **and** the comment together, so pinning doesn't mean going stale.
**Consequences:** immutable action refs (a re-pointed tag can't inject code); the cost is a Dependabot PR
when an action releases — the correct trade for a security-portfolio repo whose CI holds cloud credentials.

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

## ADR-0014 — Phase 3b: the request workflow engine
**Context:** Phases 4 (onboarding) and 5 (access) need one shared request lifecycle — aggregate, state
machine, concurrency control, and a domain-event spine — built on Phase 2 (Postgres/Flyway) and 3a
(`authz`). Reviewed by the consultant + senior architect; their conditions are folded in below.
**Decision:**
- **One `Request` aggregate** (`request.requests`) for both types; identity columns
  (`requester`/`submitted_by`) are first-class, NOT in `payload`, so SoD/ABAC never parse JSON. `payload`
  (jsonb) is opaque to the engine (type-specific shape owned by 4/5).
- **Authoritative serializer:** `UPDATE … WHERE id=:id AND status=:from AND version=:expected` succeeds
  for exactly one caller — this is the single lock for concurrent approvers AND (Phase 4) the provisioning
  work-claim (`APPROVED → PROVISIONING`, one poller wins → only it calls Graph, no double-provision).
  An **explicit `version` column** (not JPA `@Version`) because the guard must also assert "status is
  still `:from`", which `@Version` can't express; it also lets us split 409 (state moved) from 412
  (version moved) by re-reading on `rowcount=0`.
- **Check order — authorize before revealing state:** load (404) → **authz on the real principal (403)**
  → If-Match (412) → legal-from (409) → guarded UPDATE → same-tx timeline + outbox. An unauthorized
  caller never learns whether a request is stale or decidable (architect/consultant condition).
- **Events:** every transition writes a `request.request_events` row + a `messaging.outbox` row in the
  **same tx** via the generic `platform.OutboxWriter` (outbox in a shared `messaging` schema so no module
  touches another's tables; relay is Phase 6). The "event ⇔ transition" invariant is **scoped to the
  transition path** — the engine also allows **non-transition events** (`provisioning_failed`) with no
  status change so a stuck request is visible; `request_events` uses a global `bigserial` id (total order)
  so those append safely off the version-serialized path.
- **`SUBMITTED → UNDER_REVIEW` auto-advances** on submit (the frozen contract has no pickup endpoint, and
  `/review-queue` returns `UNDER_REVIEW`, so resting at `SUBMITTED` would leave the queue always empty);
  `SUBMITTED` is still recorded in the timeline.
- **No terminal `FAILED` state** (frozen enums have none) → provisioning is retry-until-success;
  `external_ref` on the aggregate lets a retry be find-or-create (idempotent onboarding), not blind
  create. A real `FAILED` state is a future CR if ops needs one.
**Consequences:** No HTTP in 3b (engine + `RequestService` only; first HTTP transition is Phase 4 with
full contract tests). Additive: V2 (platform-owned `messaging.outbox`) + V3 (request-owned `requests` +
`request_events`); no infra/TF change → an `app-deploy` image roll. Module graph stays acyclic
(`request → authz, platform`), ArchUnit-enforced. 28 tests green incl. concurrent-approver and
impersonation-laundering against real Postgres.

## ADR-0015 — Phase 4a: onboarding HTTP surface + idempotency + simulated provisioning
**Context:** the first end-to-end vertical over the 3b engine — the frozen `/applications*` + `/review-queue`
endpoints — and the cross-cutting HTTP infra they need (ETag, Idempotency-Key, read ABAC). Split 4a (this:
contract-complete, no external dependency) from 4b (real Graph provisioning, consent-gated). Reviewed by
consultant + architect; conditions folded in.
**Decision:**
- **Idempotency is claim-first** and covers **every** Idempotency-Key POST (create + submit + decision):
  an atomic `INSERT (principal, endpoint, key)` is the lock (concurrent duplicates can't both run); the
  winner stores its 2xx response (replay returns it), an error **releases** the claim (transient failures
  stay retryable), a loser sees 409 (in progress) / the stored response / **422** (same key, different
  body). The DB guard still gives exactly-once side effects; this gives replay-returns-original. 24h
  window (stale keys reclaimed at claim time). Platform-owned `platform.idempotency_keys` (V4).
- **PATCH = merge, not replace; `name`/`env` immutable** post-create. `RequestService.updatePayload` does a
  guarded status-unchanged version bump with the same **authorize-before-revealing-state** order as a
  transition (load → 403 → 412 → legal-status → guard). Read paths (`get`/`timeline`/`list`) authorize
  (app.read + ownership/scope) **before** returning — the engine reads stay unguarded, ABAC at the
  controller, with an ArchUnit guard that only `onboarding`/`review` (later `access`) may use the engine.
- **Projection fidelity:** `ApplicationCreate.uris` → `Application.redirectUris`; `clientId` ← the
  request's `external_ref`; total `RequestStatus`→`OnboardingStatus` (onboarding never reaches GRANTED).
- **`/review-queue`** is type-agnostic and gated to `review.read` (reviewers only). ETag emitted quoted;
  If-Match parsed (incl. `*`/weak) at the controller, engine takes the int.
- **Simulated provisioning to ACTIVE:** a worker (`ProvisioningService.runOnce`) polls APPROVED →
  `markProvisioning` claim (one winner) → `AppRegistrationProvisioner.provision` → `markProvisioned`. 4a
  ships `SimulatedProvisioner` (default `eop.provisioning.simulate=true`, synthetic deterministic client
  id) so the lifecycle completes with **no consent**; the scheduler is off by default
  (`eop.provisioning.scheduler`), tests call `runOnce()` directly. 4b adds the real Graph implementation
  + `tags`-based find-or-create (DB-`external_ref`-first, Graph tag `$filter` fallback) + the consent.
- **Registry deferred:** with client-ID-only provisioning and `clientId` single-sourced on `external_ref`,
  `registry` has no responsibility yet — it lands with secret minting + `secret.rotate` in the SP/secret
  fast-follow (no duplicative table now).
**Consequences / scope limits (write into the ticket/RUNBOOK):**
- **An onboarded app is a registration + client ID, NOT a sign-in-capable app.** `Application.ReadWrite
  .OwnedBy` creates the registration but **not a service principal**, so the app can't be used for sign-in
  until an SP exists (needs the broader grant / a portal step) — a clean fast-follow / future CR. This
  satisfies the contract DoD ("returns a client ID") but nobody should assume the app is live.
- An ACTIVE app's metadata is immutable (PATCH is DRAFT/CHANGES_REQUESTED only); post-activation
  management beyond the deferred `secret.rotate` is a future `registry` refinement. Acceptable for v1.
- Additive (V4 migration + new modules); no infra/TF change in 4a → an `app-deploy` image roll. 40 tests
  green incl. onboarding lifecycle, idempotency, read ABAC, and simulated provisioning to ACTIVE.
- **Known trade-off (idempotency atomicity):** claim→action→complete are three commits — PENDING is
  visible immediately so a concurrent loser gets an instant 409 rather than blocking for the whole action.
  The cost: a crash after the action commits but before `complete()` leaves the key PENDING (retries →
  409 until the 24h reclaim, side effect orphaned from the key). Acceptable for v1 (bounded by TTL); the
  alternative (one big transaction) makes losers block. (Architect review of PR #80.)
- **Contract precision follow-up:** the idempotency layer can return 422 (key reused, different body) on
  the transition POSTs, but the frozen per-endpoint response lists for `/submit`, `/decision`, etc. don't
  enumerate 422 (it's documented globally in the contract `info` block). Closed by a follow-up CR adding
  422 to the idempotent POSTs' response lists — a contract change, so via the change-request governance.

## ADR-0016 — Phase 4b: real Entra app-registration provisioning (Graph over WIF) + the reaper
**Context:** swap 4a's `SimulatedProvisioner` for a real Microsoft Graph write so onboarding approval
creates an actual Entra **app registration** and records its **client ID**. Consent-gated and externally
dependent (the half 4a isolated). The 4a workflow / idempotency / ABAC / `markProvisioning`-claim are
unchanged; 4b adds the provisioner, the stuck-provisioning reaper, and flips the flags. Reviewed by
consultant + architect; must-fixes folded in.
**Decision:**
- **`directory.GraphProvisioner`** (active when `eop.provisioning.simulate=false`, which also requires
  `wif.enabled=true`) creates the registration via Graph `POST /applications` using the app-only token
  from `WifAssertionService.graphToken()` — **no new credential**. Reuses v0's `GraphService` resilience
  idiom (429/`Retry-After` backoff, opaque-403 = missing consent). `signInAudience=AzureADMyOrg`
  (single-tenant, matches the portal). `displayName` = the onboarding app's **name**; the **requestId tag
  is the dedup key** (never dedup on displayName — not unique).
- **Idempotency = find-or-create keyed on the request id**, carried as `tags:["eop:requestId:{uuid}"]`
  (architect's call over `identifierUris` — purpose-built, `$filter`-queryable, no side effects). The
  `tags/any(...)` `$filter` is an **advanced query** → `ConsistencyLevel: eventual` + `$count=true`.
  Three layers, strongest first: (1) **DB `external_ref` first** (worker reuses a recorded client id, no
  Graph call); (2) **Graph tag `$filter`** fallback inside `provision`; (3) the reaper outlasts `$filter`
  eventual-consistency lag.
- **Find-or-create is the atomic retry unit** (architect must-fix — the within-call dup window): a create
  whose response is lost (timeout/5xx) is NOT bare-retried; the loop **re-runs the tag find first**, so a
  committed-but-unacked app is reused, not duplicated. Graph doesn't enforce tag uniqueness, so a `$filter`
  match of >1 is resolved **deterministically** (earliest `createdDateTime`, tie-break `appId`) and **loudly
  logged** (`DUPLICATE … for tag`) so a slipped-through dup stays detectable.
- **Stuck-provisioning reaper** (architect must-fix): a request whose task died mid-provision stays
  `PROVISIONING`; the worker re-claims and re-provisions it. **Lease** (`next_attempt_at`, armed by
  `markProvisioning` to now+`lease-seconds`, default 300 — **sized above worst-case provision duration**)
  stops a slow-but-alive worker being double-called; NULL = due now (fail-safe). **Exponential backoff**
  (`provision_attempts`, capped at `backoff-cap-seconds`, default 3600) stops a permanently-failing request
  looping hot — needed because there is no terminal `FAILED` state (frozen enums) so retry is forever. The
  reaper re-claim is **guarded on version** (same serializer idiom) so two reapers can't both call Graph.
  New columns via `V5`.
- **Flag flip is TF-gated, consent-ordered:** `provisioning_real` var (default `false`) — the first apply
  only **declares** `Application.ReadWrite.OwnedBy` on `eop-dev-app`; after a GA grants admin consent, flip
  the var true (sets `EOP_PROVISIONING_SIMULATE=false` + `EOP_PROVISIONING_SCHEDULER=true`, rolls the task).
  **Consent MUST precede the token mint** — the `.default` token caches consented perms at mint time, so a
  pre-consent task 403s until the cache expires.
**Consequences / scope limits:**
- **Least privilege: `OwnedBy`, not `ReadWrite.All`** → a registration + client ID, **not a service
  principal**. The onboarded app isn't sign-in-capable until an SP exists; SP + secret + `secret.rotate` +
  `registry` remain the deliberate fast-follow / future CR (an SP needs the broader grant, withheld here).
- **Verify-at-first-apply (only provable live, log them):** the `$filter` advanced-query headers return 200
  (not unsupported); the **list** `GET /applications?$filter` is permitted under `OwnedBy`; a forced
  re-provision creates **no second app**. (CR-1416-item-3 style.)
- Additive + one TF change (entra permission + service env toggle) + `V5` migration. 47 tests green (+7:
  GraphProvisioner find-hit/miss/429/403/duplicate against a mocked Graph; reaper recovery + backoff).
  Real end-to-end is the post-merge apply + GA consent + manual verify (like v0 Flow-2).

## ADR-0017 — Phase 5a: access governance core (catalog, requests, my-access, removal) + simulated group provisioning
**Context:** the second vertical over the 3b engine — self-service access governance. Reuses the engine
(`RequestType.ACCESS` already auto-advances create → UNDER_REVIEW; `provisionedStatus=GRANTED`), the
frozen RBAC matrix (all access permissions already in `RolePermissions`), and the 4b provisioner-port +
reaper/backoff. Split (mirrors 4a/4b): **5a** = contract-complete, simulated provisioning, no consent;
**5b** = real `GroupMember.ReadWrite.All` group writes (consent-gated); **5c** = teams (separate slice —
direct CRUD, no engine). Reviewed by consultant + architect; must-dos folded in.
**Decision:**
- **`access` type-module over the engine** (mirrors `onboarding`): `AccessController` →
  `/catalog*`, `/access-requests*`, `/my-access*`. Create validates the catalog resource, denormalizes
  `resourceName`/`mappedGroup`/`risk` into the engine payload (`kind=grant`), and `engine.create(ACCESS)`
  (auto-advances to UNDER_REVIEW — no separate submit). Decision routes to `engine.decide` (ACCESS_DECIDE
  + **SoD on the real principal** + If-Match). **Read ABAC at the controller** (engine reads unguarded);
  `access.read` `✔(own)` → owners see only their own. `Idempotency-Key` via `platform.IdempotencyService`.
- **`access_grant` projection (`V6`) is the source of truth for "currently held"** (`removed_at IS NULL`),
  **NOT** the request status — a removal request ends in `GRANTED`-meaning-"completed" (frozen enums have
  no `REMOVED` state). `GET /my-access` reads it. A partial-unique index enforces at-most-one active grant
  per (user, resource).
- **Atomic completion** (architect must-fix): `AccessGrantService` (a SEPARATE bean so the `@Transactional`
  proxy applies) wraps `engine.markProvisioned` (request schema) + the `access_grant` write (access schema)
  in ONE transaction — no GRANTED-without-/my-access drift. The Graph/simulated call happens in the worker
  BEFORE the tx.
- **`expires_at` persisted (= `granted_at` + `duration`), NOT enforced** (architect must-do): duration is
  **informational in v1**; persisting `expires_at` now makes the future expiry sweep a pure add (no
  migration) — [[CR-20260628-2240]].
- **Type-scoped worker/reaper** (architect must-fix): `findStaleProvisioning` (+ the APPROVED poll) now
  take a `RequestType`, and `AccessProvisioningService` mirrors onboarding's worker but scoped to ACCESS —
  so the onboarding worker never reaps an access row (it would try to register an app for it) and vice
  versa. Same guarded claim + lease + exponential backoff.
- **`directory.GroupMembershipProvisioner` port** + `SimulatedGroupProvisioner` (default-on → synthetic
  grant ref, no Graph, no consent); the worker branches on `kind` (grant=addMember, removal=removeMember).
  5b adds the real Graph impl, idempotent by **specific** error code (add→already-exists→ok,
  remove→not-member→ok; bad-group-id 400 still surfaces).
- **Catalog is read-only** (`V6` table + dev seed); management is a future admin CR (forced by the freeze —
  only `GET /catalog*` exists). `mappedGroup`s are placeholders in 5a; 5b binds them to manually-created
  Entra group object ids.
**Consequences / scope limits / CR candidates:**
- **Access `REQUEST_CHANGES` dead-ends** — no `/access-requests/{id}/submit` in the freeze; v1 = "open a new
  request" ([[CR-20260628-2235]]).
- `kind` filter on `GET /access-requests` is applied **in-memory** on the page (kind lives in the payload,
  not a column) — minor pagination imprecision, acceptable at v1 dev scale.
- **Duplicate-grant edge case (architect PR #98 review):** the active-grant unique index could trap a
  double-approved request in PROVISIONING (unique violation → rollback → reaper loop). Closed at both ends:
  `create()` rejects a grant for an already-held resource (**422**); `completeGrant` skips the insert when an
  active grant already exists for (user, resource) — so a double-approval never trips the index and, in the
  rare truly-concurrent case, self-heals on the next reaper pass instead of looping.
- Known matrix gaps unchanged: no lean requester role; `ROLE`/`TEAM` catalog = governance groups not
  portal-role elevation; approvers can't swap role/scope at decision (frozen `Decision` enum).
- Additive: `V6` migration + new `access` module + the type-scope engine tweak; no TF/consent in 5a. 57
  tests green (+10: access lifecycle/SoD/read-ABAC/removal/my-access/catalog + reaper + type-scoping).
  Real group writes + consent are 5b.

## ADR-0018 — per-vertical provisioning flags (fix the shared-`simulate`-flag deploy crash)
**Context:** activating 4b (set `eop.provisioning.simulate=false`) crash-looped the task. That single flag
gated **both** simulators — `SimulatedProvisioner` (onboarding) **and** `SimulatedGroupProvisioner`
(access, shipped in 5a). Flipping it false turned on the onboarding real bean (`GraphProvisioner`, exists)
but turned **off** the access simulator whose real counterpart (5b `GroupMembershipProvisioner`) doesn't
exist yet → `AccessProvisioningService` had an unsatisfied dependency → context init failed. The OLD task
(`simulate=true`) kept serving, so no outage, but real provisioning never activated. **CI was green because
tests run with the `matchIfMissing=true` simulated defaults — `simulate=false` wiring is only exercised on
a real deploy.** (Architect caught it on the live apply.)
**Decision (forward-fix):**
- **Split `simulate` per vertical:** `eop.provisioning.onboarding.simulate` and
  `eop.provisioning.access.simulate`; each vertical's simulator/real-provisioner keys on its own flag. A
  vertical whose real impl doesn't exist stays simulated (wired) regardless of the other. Same split for
  the scheduler flag (`…onboarding.scheduler` / `…access.scheduler`) for symmetry.
- **Schedulers run in the deployed task regardless** (set in the service env); the activation is *only* the
  per-vertical `simulate` flip. So a still-simulated vertical keeps completing (no access-demo regression).
- **TF:** `provisioning_real` → two independent vars `onboarding_provisioning_real` (true now, 4b) and
  `access_provisioning_real` (false until 5b); each flips only its vertical's `EOP_PROVISIONING_*_SIMULATE`.
- **Regression guard:** `ProvisioningWiringTest` boots the full context in the exact crashing combo
  (onboarding `simulate=false`, access simulated; `WifAssertionService` mocked since `wif.enabled` is off in
  tests) and asserts the right beans wire — so this class of flag-only deploy-wiring break is caught in CI,
  not on a live apply.
**Consequences:**
- Forward-fix un-sticks the rollout: the new image (renamed flags) + per-vertical env → the next task boots
  clean with onboarding real, access simulated. No rollback needed (old task kept serving meanwhile).
- **Lesson:** a shared conditional flag across independently-activated verticals is a latent deploy trap
  that unit/Testcontainers (simulated-default) tests can't see — boot-with-real-flag smoke tests are the
  guard. Carry this pattern to any future vertical (audit/notify/teams).

## ADR-0019 — Phase 5b: real Entra group-membership provisioning (Graph over WIF)
**Context:** swap `SimulatedGroupProvisioner` for the real one so an approved access request adds the
requester to the resource's Entra group (and a removal removes them), over the WIF token with
`GroupMember.ReadWrite.All`. The consent-gated half of access; everything around it (worker, reaper,
`access_grant` projection, atomic completion, per-vertical `access.simulate` flag, `access_provisioning_real`
TF var) shipped in 5a + the flag-fix. Reviewed (Phase 5 + 5b note); architect must-dos folded in.
**Decision:**
- **oid was already canonical** — `PrincipalFactory` maps `realUserId` from the `oid` claim (object id),
  not the app-pairwise `sub`. So SoD/ABAC/audit AND the new group-member id all key off `oid` (a real
  directory object). No principal change needed — the load-bearing 5b risk was already handled in 3a.
- **`directory.GraphGroupMembershipProvisioner`** (active `eop.provisioning.access.simulate=false`; needs
  `wif.enabled=true`): `POST /groups/{id}/members/$ref` (body `@odata.id` → `directoryObjects/{oid}`) and
  `DELETE …/members/{oid}/$ref`. Reuses the 4b 429/`Retry-After`/backoff idiom.
- **Idempotent by specific Graph signal** (architect must-fix, NOT blanket 400/404): add→already-member is a
  400 with message "…already exist" → success (tolerant, case-insensitive — Graph gives no distinct code);
  remove→not-member is a 404 → success; a 404 on add (bad group/object) and any other 400 are rethrown
  (surface). **403 is its own loud "missing GroupMember.ReadWrite.All consent" error**, never folded into
  the 400 path. Composes with 5a's `completeGrant` (findByRequestId + findActive) → Graph-idempotent +
  DB-idempotent = reaper/retry safe end-to-end.
- **addMember returns a deterministic marker** (`{groupId}:{oid}`) since Graph add is 204 (no id) — this
  arms the worker's DB-first `external_ref` skip so a re-provision doesn't re-call Graph (force the Graph
  path in live idempotency tests by nulling `external_ref`).
- **Symmetric wiring guard:** `ProvisioningWiringFullyRealTest` boots with BOTH `onboarding.simulate=false`
  AND `access.simulate=false` and asserts both real provisioners wire — closes the symmetric form of the 4b
  shared-flag crash (a future access flip can't silently break wiring).
- **TF:** `modules/entra` declares `GroupMember.ReadWrite.All` (GUID `dbaae8cf-…`, **resolved live**);
  activation is `access_provisioning_real=true` → `EOP_PROVISIONING_ACCESS_SIMULATE=false`. Consent granted
  the same surgical `appRoleAssignment` way as OwnedBy; declare → consent → flip.
**Consequences / scope limits:**
- **Tenant-broad grant:** Graph has no per-group app-only scope for membership writes, so
  `GroupMember.ReadWrite.All` lets the app touch ANY group. Acceptable for v1 dev; constrain/monitor for
  prod ([[CR-20260629-0030]]).
- **Dev test groups must be static (assigned) security groups** — not dynamic (manual add → 400) and not
  role-assignable (membership writes need extra privilege → 403). Mapped-group object ids are injected at
  test time (not committed) — catalog management is a future admin CR.
- Real end-to-end is the post-merge apply + GA consent + seed-and-watch (grant → in group → /my-access;
  removal → out of group → `removed_at`, request status GRANTED-means-completed), mirroring the 4b sign-off.
- Additive: one new provisioner + TF permission + tests; the access machine is unchanged. Build green.

## ADR-0020 — Phase 5c: teams (portal-local CRUD; group-backing deferred); ABAC team-delegation
**Context:** the `/teams*` surface — create teams, manage membership, soft-delete (audited). Architect
routing CR-20260629-0723 (Accepted). Teams are a different shape from onboarding/access: **direct CRUD, no
request engine** (no approval/SoD/provisioning lifecycle).
**Decision:**
- **Option A — portal-local teams.** Teams + members are DB rows (`teams` schema, `V7`); member add/remove
  is soft-delete. **No Graph, no new consent.** (B) full Entra-group-backed teams is **deferred** —
  `POST /teams` creating a group needs the broad `Group.ReadWrite.All`, withheld on least-privilege. (C)
  optional group reflection is **deferred to ride the Phase 6 outbox relay** — its cost is dual-write
  consistency, not consent, so the right shape is `team.member.added/removed` event → Phase 6 relay →
  `GroupMembershipProvisioner` (reusing 5b's grant), not a synchronous Graph call in `TeamService`.
- **`teams` module depends only on `authz` + `platform`** — never `request` (ArchUnit `teams_module_boundary`,
  Pin D). No engine.
- **ABAC: `TeamEntity implements Ownable`** — `ownerId()`=creator oid, `teamMemberIds()`=active member oids.
  **`team.read(own)` = creator OR active member** (closes the "member can't see their own team" gap), but
  **`team.manage(own)` = creator-only** (members read-only — non-viral delegation, forward-safe for when app
  co-ownership lands). The shared `Ownable.owns()` (ownerId OR teamMemberIds) can't express manage≠read in
  one method, so the distinction is the load: read ops load the team WITH active members (creator-or-member);
  manage ops load it WITHOUT (empty `teamMemberIds()` → `owns()` = creator-only). ADMIN/SUPER unaffected (ALL
  scope). (Architect PR #123 sign-off.)
- **Pin A — co-ownership is `TeamEntity`-only in v1.** `Application.team[]` lives in the request payload,
  and `RequestEntity` deliberately keeps the empty `teamMemberIds()` default (the engine never parses
  payload for ABAC). So onboarding apps get **no** team co-ownership yet (regression-guarded). Wiring it is
  the deferred **Pin B** `TeamMembershipResolver` port (defined in `authz`, implemented in `teams`,
  `@ConditionalOnMissingBean` no-op default) — the only boundary-legal shape, built when `team` is promoted
  to a first-class column.
- **Audit now, Phase 6 consumes (Pin C):** `OutboxWriter.append` with aggregateType `team`, event types
  `team.created`/`team.member.added`/`team.member.removed`, payload `{teamId,userId,actorId,occurredAt}` —
  frozen so the Phase 6 relay needn't re-rev. Same single-writer outbox the engine uses.
- **Idempotency:** the `(team_id,user_id) WHERE removed_at IS NULL` partial unique index makes add/reactivate
  the atomic unit (idempotent even without the key); `Idempotency-Key` is claim-first dedup on top. Re-add
  reactivates the row (history lives in the outbox events). Name is tenant-unique → 409.
**Consequences / scope notes:**
- **No `DELETE /teams/{id}` in the frozen contract** — only member soft-delete. So there is intentionally
  **no `teams.deleted_at`/cascade** (CR §3's premise); team deletion would be a contract CR (deferred). No
  dead schema added.
- `TeamMember.name` (display name) isn't resolved in v1 portal-local teams (no directory lookup) — returned
  null; the frontend resolves names separately.
- Additive: `V7` + new `teams` module; no infra/consent. Contract untouched (Spectral green).

## ADR-0021 — Phase 6a: hash-chained audit log + the single-leader outbox relay
**Context:** the internal half of Phase 6 — `GET /audit` (filtered, cursor-paged) + `GET /audit/verify`,
fed by draining `messaging.outbox` (accumulating `request.*`/`team.*` since Phase 3b). No external
dependency, so it ships without a consent/verification gate (notify + SES are 6b). Architect review
green-lit the design note with four decisions + a fifth (backlog) + three correctness must-addresses.
**Decision (the four + backlog, as the architect ruled):**
- **(1) Actor enrichment = enrich-at-emit.** The relay runs in a background thread with **no principal
  context**, so it cannot attribute — the actor must travel in the event. `RequestService`/`TeamService`
  emit now carry `actor` (real principal) + `effectiveRole` (nullable); worker transitions emit
  `actor="system"`; the relay coalesces `actor → actorId → "system"` so legacy rows can't NPE. (Rejected:
  a relay-side read-port — it would reach across modules and still lack a principal.)
- **(2) DB immutability = trigger now (V8) + role REVOKE in Phase 10.** `audit.audit_events` has a
  `BEFORE UPDATE OR DELETE` trigger that `RAISE`s — works regardless of connect role (belt-and-suspenders).
  The real least-priv guarantee (app connects as a role with UPDATE/DELETE revoked) rides the Phase 10
  DB-roles work; until then immutability is the trigger only (documented as partial).
- **(3) Relay shape = single advisory-lock leader.** The audit chain is linear, so one writer is mandatory.
  Elected by a Postgres **session-level** `pg_try_advisory_lock` held for the whole tick on a **dedicated
  connection** (acquire→work→release-in-finally; connection close also releases → clean failover). A
  non-leader tick is a no-op. `FOR UPDATE SKIP LOCKED` was rejected for audit (parallel claimers can't order
  a chain) — it's reserved for 6b's order-independent notify fan-out. (Must-address **D**: connection
  ownership is explicit; the lock never rides a pooled connection that could be handed back mid-tick.)
- **(4) `at` = event `occurred_at`** (when it happened), not relay processing time. Added a separate
  `audited_at DEFAULT now()` (relay wall-clock) purely for lag monitoring — cleanly separates the two.
- **(5) Pre-6a backlog = fast-forward, don't backfill.** The outbox rows from 4b/5b/5c live runs predate
  enrichment (degraded attribution) and are throwaway test data, so `V8` sets `published_at = now()` on all
  then-unpublished rows: the chain starts **clean** at first 6a deploy rather than polluted. The relay still
  carries the missing-actor fallback for any row an old task emits mid-deploy. (`published_at` is consumed
  only by the relay; provisioning reads request status, so the fast-forward can't affect in-flight work.)
**Correctness must-addresses (folded into the build):**
- **(A) jsonb canonicalization round-trip.** The hash pre-image is canonicalized by one shared `AuditHasher`
  used at BOTH insert and verify (sorted keys, recursive), so verify re-hashes a faithful row identically.
  `detail` is stored as `jsonb` but its values are **strings only** so a store→read→re-canonicalize is
  byte-stable (jsonb normalizes numbers / drops dup keys). Originally an emit-site convention; on architect
  hardening note #1 the **projector now coerces every detail value to its string form** before
  hashing/storing, so verifiability is independent of emit discipline (a future numeric field can't silently
  break verify). Guarded by a golden-vector unit test (pinned canonical string), a Postgres round-trip
  integration test, **and** a numeric-detail-coercion test.
- **(B) `seq` is not gap-free.** `GENERATED ALWAYS AS IDENTITY` consumes values on rollback (the 23505
  idempotency path, any transient failure), so seq has gaps. Harmless: integrity is `prev_hash` linkage, not
  contiguity. `seq` is deliberately **excluded from the hash pre-image** (DB-generated, unknowable
  pre-insert, and can't be patched post-insert because the row is immutable). `/audit/verify` walks
  `ORDER BY seq` tolerating gaps. No client may assume contiguity (documented in STATUS + INTEGRATION-NOTES).
- **(C) 23505 needs no shared transaction.** Each handler (`AuditProjector.handle`) is its **own**
  `@Transactional`; the relay bean is **not** transactional and `markPublished`/`backoff` are autonomous. So
  a duplicate-`source_event_id` insert aborts only its own tx and surfaces as `DuplicateKeyException`, which
  the relay swallows **per-handler** — no aborted transaction is ever reused (the savepoint requirement is
  met structurally, and per-handler catch keeps 6b's notify handler independently idempotent).
- **(E) Poison visibility.** A transient handler failure defers the row (exponential backoff, reusing the
  reaper's cap) and **stops the tick** (audit must not skip) — so a stuck row halts the whole chain, which
  for audit is correct but a compliance hazard, so it is logged loudly past `POISON_ATTEMPTS` (full
  dead-lettering deferred to 6b).
**Module/contract:** new `audit` module (ArchUnit `audit_module_boundary` → `authz`+`platform` only); the
relay + `OutboxEventHandler` SPI + `OutboxRecord` are platform-owned (dependency inversion — platform never
depends on audit). `EOP_RELAY_SCHEDULER=true` always-on in deploy (no gate). Contract untouched (the
`/audit*` shapes were frozen in v1). Minor/deferred: `resource_type` alone can't tell onboarding from access
(both are the `request` aggregate) — `detail.type` (RequestType) distinguishes them; `/audit/verify` is O(n)
from genesis (fine for v1/dev; checkpointing deferred to prod scale).

## ADR-0022 — Phase 6b: in-app notifications via a second, decoupled outbox consumer
**Context:** the external half of Phase 6 — `GET /notifications`, `POST /notifications/{id}/read`,
`POST /notifications/read-all`, fed from the same `messaging.outbox` as audit. Architect review green-lit
the design note with decisions 1–5 as recommended and catches A–D folded in.
**Decision:**
- **Separate consumer, not a handler (decision 1 — the load-bearing one).** Notifications are derived by a
  standalone `notify.NotifyRelay`, NOT by adding a handler to the 6a single-leader audit relay. If notify
  rode the audit relay, an SES/notify failure (which defers-and-stops the tick) would freeze the
  tamper-evident audit chain. So notify is fully decoupled: its own poller, its own outbox markers
  (`notified_at`/`notify_attempts`/`notify_next_attempt_at`, distinct from audit's `published_at`/`attempts`),
  its own failure handling. A row is fully consumed only when BOTH `published_at` and `notified_at` are set.
- **`FOR UPDATE SKIP LOCKED` fan-out.** Notifications have no chain, so order is irrelevant — N tasks claim
  disjoint rows in parallel (no advisory lock, no single leader). This is the SKIP-LOCKED case 6a reserved.
- **Per-row, single-transaction claim→project→mark (catch C).** Each row is claimed, projected, and
  `notified_at`-marked in ONE tx; SKIP LOCKED makes that safe and removes the redispatch window.
  `UNIQUE(source_event_id, recipient)` stays as defense for a crash before commit. (Audit needed a split-tx
  dance ONLY because of its chain; notify doesn't.) A poison row is deferred (backoff) and the consumer
  CONTINUES — order-independence means one bad row never blocks others (unlike audit, which must stop).
- **`createdAt` = event `occurred_at` (catch A), not insert time.** With lagging SKIP-LOCKED consumers,
  ordering the feed by insert time would dump a clump of "now"-stamped rows at the top after a stall — stale
  events masquerading as new. occurred_at keeps the feed causally stable regardless of consumer timing.
- **Feed + read-state scoped to the REAL principal (catch B).** `realUserId`, never the effective identity —
  a Super Admin impersonating sees/marks THEIR own feed (same ABAC-on-the-real-principal rule as audit/SoD).
- **Self-suppression (catch D).** `recipient == actor` → no notification (don't tell someone about their own
  action, e.g. a Super Admin who adds themselves to a team).
- **v1 recipient model (decision 4 + architect post-approval note).** Notify the individual already in the
  event — requester on **terminal/decision** outcomes `request.{approved,rejected,changes_requested,active,
  granted}`, affected member on `team.member.{added,removed}`. **`request.provisioning_failed` is
  deliberately NOT notifiable** — provisioning auto-retries (no terminal FAILED state in v1), so notifying on
  it would be repeat noise + a confusing problem-then-success sequence; a real dead-letter event is the right
  trigger if ops wants failure alerts. No directory enumeration. The **reviewer-queue fan-out** (role→all
  reviewers) is **deferred** — it needs the same Graph directory capability as oid→email; light them up
  together (CR-20260629-1610).
- **In-app is the v1 channel; SES deferred (decisions 2+3).** The in-app feed (guaranteed, no external dep,
  no consent) ships now, always-on (`EOP_NOTIFY_SCHEDULER=true`). **SES email + oid→email resolution + the
  retry/dead-letter/TF SES module are deferred** to a tracked follow-up (CR-20260629-1610) — they add an
  external dependency, a Graph consent (`User.Read.All`), and a human SES-identity-verification step, none of
  which should gate the in-app feature. `read`/`read-all` need no Idempotency-Key (naturally idempotent);
  mark-read of a non-owned notification → 404 (no existence leak).
**Module/contract:** new `notify` module (ArchUnit `notify_module_boundary` → `authz`+`platform` only; NOT
`directory` in v1 — that arrives with the deferred Graph work). Contract untouched (the `/notifications*`
shapes were frozen in v1). V9 = `notify.notifications` + the outbox notify-markers + the **backlog
fast-forward** (`notified_at = now()` on existing rows so notify starts clean — no retroactive burst for the
4b/5b/5c runs or the 6a live-verify seq-1 row). **Tests:** the decoupling proof is first-class and asserted
**both directions** (a failing notify defers its own row without touching the audit chain; notify advances
while audit hasn't processed the row). Minor/forward (noted, not built): two consumers mean the outbox grows
until BOTH markers are set — an aged-out cleanup job is a Phase-10/prod concern.

## ADR-0023 — Phase 7: assistant stub (501 behind the auth gate)
**Context:** the frozen contract has `POST /assistant/chat` and `POST /assistant/actions/{id}/approve`, both
documenting a **501**. The real wizard (RAG + tools + human-in-the-loop write-actions) is an explicitly
deferred track — its whole-feature design + threat model + guardrails are written up in
`docs/assistant-feature-design-and-guardrails.md` (research/opinion doc, PR #152). v1 ships only the stub.
**Decision (the three stub decisions, as recommended in the Phase 7 note):**
- **Gate auth BEFORE the 501.** Each endpoint: 401 if no session → `authz.require(ASSISTANT_USE)` → 403 for
  a role lacking it (AUDITOR/READ_ONLY) → otherwise **501**. Rationale: keeps the server authoritative and
  consistent with every other endpoint, makes the real build a drop-in (the gate is already correct), and an
  un-permissioned role never even learns the feature is unimplemented. *Nuance accepted:* these two endpoints
  don't *enumerate* 403 in their `responses` (only 200/422/501 and 202/501); a 403 from the cross-cutting
  permission layer is standard and the FE already hides the assistant from un-permissioned roles, so this is a
  conscious, low-risk choice over the stricter "501 for any authenticated caller."
- **Stub ignores 422 + Idempotency-Key.** "Not implemented" precedes "is your body valid," and there's
  nothing to replay — so the stub does not bind/validate the body or touch the idempotency store. The 422 +
  idempotency wrap the endpoint when the real assistant lands.
- **RFC-7807 problem+json 501 on BOTH** via a new reusable `platform.NotImplementedException` → one
  `ApiExceptionHandler` arm (same machinery as our other RFC-7807 errors). `/chat`'s 501 is documented as
  problem+json; `/actions/{id}/approve` documents a bare 501 — returning the same problem+json there is a
  harmless superset and keeps one code path.
**Module/contract:** new `assistant` module — `AssistantController` only, no service/persistence/migration/TF/
flag (ArchUnit `assistant_module_boundary` → `authz`+`platform` only). `Permission.ASSISTANT_USE` already
existed. Contract untouched (the shapes + 501 were frozen in v1). **Tests** (WebMvcTest, no DB): both
endpoints → 501 authorized / 403 unpermissioned (AUDITOR+READ_ONLY) / 401 no-session, confirming the gate
ordering and that the stub ignores the request body. **Forward:** the real assistant is its own phase/track
with its own threat review — the design doc's autonomy ladder starts at this stub (Rung 0).

## ADR-0024 — Phase 8: high availability (run ≥2 tasks) + the WIF issuer-key single-writer fix
**Context:** make the app tier survive the loss of one task/AZ by running **≥2 Fargate tasks**. Most of HA
was already designed in (guarded serializer, advisory-lock audit leader, SKIP-LOCKED notify, guarded
provisioning claims + reaper, idempotency, Redis sessions, dependency-free `/healthz`), so Phase 8 is the
operational glue + one latent multi-task hazard. Architect endorsed all four design decisions + three
must-adds.
**Decision (the four, as recommended):**
- **Fixed `desired_count = 2`, autoscaling deferred.** Autoscaling ≠ HA; HA is "survive one task/AZ". Two
  private subnets span two AZs → Fargate spreads one task per AZ. (service-module `desired_count` var, default 2.)
- **Data-tier multi-AZ = a prod tfvars flip; dev stays single-AZ.** `var.multi_az` (root → data + cache)
  already exists; `dev.tfvars` sets it `false` explicitly (RDS single-AZ + single-node Redis = a session
  SPOF, not a workflow outage). Prod sets `multi_az = true` (RDS Multi-AZ + Redis replica + automatic
  failover); the app already tolerates a failover blip (Flyway `connect-retries`, Hikari
  `initialization-fail-timeout=-1`, Redis reconnect).
- **Graceful shutdown:** `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=25s`
  (drain in-flight requests + stop `@Scheduled` pollers cleanly on SIGTERM) + ALB target-group
  `deregistration_delay=30`. A tick cut mid-flight stays safe (the reaper re-claims; relays idempotent).
- **Single cohesive phase** (config + verify), not split.
**Must-adds (folded in):**
- **(1) WIF issuer-key single-writer — the one thing that can silently break provisioning.** A naive 2-task
  cold-start would have each task generate its OWN RSA key, `putSecretValue` (last-write-wins) and publish
  its own `jwks.json` (overwriting the single object) — so the loser's assertions carry a `kid` not in the
  published JWKS and **fail at Entra**, breaking app→Graph provisioning intermittently. Doesn't bite *this*
  scale-up (the dev key is already provisioned) but is a latent landmine (fresh-env cold-start + rotation,
  issue #77). **Fix:** `IssuerKeyService` now holds a Postgres **advisory lock** (`ISSUER_KEY_LOCK`, the same
  primitive as the audit relay) around the get-or-generate, so exactly one task generates+stores and the
  others block then LOAD — all tasks converge on one `kid`. DB is available (the issuer publishes from an
  `ApplicationRunner` after Flyway; deploy always runs `data` alongside `wif.enabled`). `wif` gains a
  `javax.sql.DataSource` dep (ArchUnit still green; no cycle). Rotation should remain an out-of-band
  single-writer op; the lock makes an accidental concurrent rotation safe too. Guarded by
  `IssuerKeySingleWriterTest` (two instances + real Postgres → exactly one generate, same kid).
- **(2) Graceful-shutdown hardening:** the ECS container **`stopTimeout=60`** is set ABOVE the 25s drain so
  ECS doesn't SIGKILL mid-drain (default 30s is too close). Flagged for the verify pass: on Fargate SIGTERM
  and ALB deregistration happen together, so a ~1–2s window of requests can 502; if the "no dropped
  requests" check shows drops, the known fix is a SIGTERM pre-delay entrypoint trap (sleep a few seconds
  before forwarding SIGTERM to the JVM so the LB deregisters first). Noted, not pre-built.
- **(3) Concurrent-boot Flyway is safe (on record):** with 2 tasks starting together both run Flyway, but
  Flyway takes a migration lock → exactly one applies, the others wait; the V8/V9 backlog fast-forwards are
  idempotent `UPDATE`s regardless.
**Also:** ECS deployment **circuit breaker** (enable+rollback) + `minimum_healthy_percent=100` /
`maximum_percent=200` for safe rolling deploys; Hikari `maximum-pool-size=10` pinned (per-task connection
ceiling explicit; 2×10 ≪ db.t4g.micro max_connections; scaling must re-check). **Contract untouched, no
migration.** **Verification (post gated apply):** both tasks register healthy + round-robin; **both report
the same issuer `kid` and the live JWKS contains exactly that key** (proves WIF multi-task-correctness);
concurrent approvals → one winner; audit chain stays single-writer (`/audit/verify` valid) with relays on
both tasks; kill a task mid-provision → reaper completes; kill the audit leader → another acquires the lock;
rolling deploy drops no requests.

## ADR-0025 — Online, backward-compatible (expand/contract) migrations as a standing rule
**Status:** Accepted. Adopted independent of Phase 9 — it is already in force today (see Context).

**Context:** Every deploy runs the OLD and NEW app image concurrently against the SAME RDS/Redis for a
window. This is NOT introduced by blue/green — Phase 8's **rolling** deploy already does it: with
`deployment_minimum_healthy_percent=100` / `maximum_percent=200`, ECS starts the new tasks (which have run
their Flyway migrations) before draining the old ones, so the old image serves against the already-migrated
schema. Blue/green (Phase 9, ADR-0024-adjacent, issue #78) only makes that window **longer** (the bake) and
more **explicit** — it does not create the constraint. Consequently the "blue/green gives a no-mixed-version
window" headline does NOT hold for this system (shared data tier); the real wins of blue/green are
pre-traffic smoke validation + instant shift-rollback, not schema isolation.

**Decision:** Database migrations are a **standing v1+ rule**, enforced by review and a soft CI guard:
1. **Backward-compatible in shape (expand first).** A migration must not break the *previous* app version
   that is still serving during the deploy: additive columns/tables/indexes only; **no** destructive or
   tightening DDL (`DROP COLUMN/TABLE`, `RENAME`, `ALTER COLUMN ... TYPE`, `SET NOT NULL` without a
   backfilled default) in the **same** release as the code that depends on the new shape.
2. **Two-release expand→contract sequence.** Introduce the new shape additively in release **N**; remove or
   tighten the old shape in release **N+1**, once no task running the old image remains. Destructive DDL is
   *allowed* — only deferred to the contract release.
3. **Online / lock-safe DDL, not just additive in shape.** Because green runs Flyway while blue serves, a
   migration that is shape-compatible but takes a heavy lock or rewrites a table (a non-`CONCURRENTLY` index
   on a large table, a volatile-default column add) will **block the old version** for the duration. DDL
   must be online/lock-safe on production-sized data. (A non-issue on tiny dev data; real in prod — the rule
   is written for prod.)

**WIF analogue (issuer-key rotation is deploy-free).** The same hazard exists for the WIF issuer signing key:
during a deploy, if the new image **rotates/introduces a new key** it publishes a new `jwks.json`, and the
old image — still signing assertions with the old `kid` — breaks (its `kid` is no longer in the JWKS). The
ADR-0024 advisory-lock writer (`ISSUER_KEY_LOCK`) makes *accidental concurrency* safe (one writer) but does
NOT make a *rotation-during-deploy* safe. **Rule:** issuer-key rotation is a standalone, deploy-free
operation — never coincident with a deploy. **Smoke-gate refinement (for when blue/green ships):** the
pre-shift gate must assert green **adopts the `kid` the prior version was already serving** (i.e. no new key
introduced during the deploy), NOT merely "green's `kid` == live `kid`" — the latter passes even when green
just published a brand-new key that has already broken blue. The gate must also include a **blue-health
check after green's Flyway runs** (prove the still-serving old version works against the now-migrated schema
*before* any traffic shift).

**Enforcement:** `scripts/check-migrations.sh` (wired as the `migrations` job in `ci.yml`) scans the
migration files a PR adds/changes for destructive or lock-heavy DDL and emits GitHub **warnings** (a
reviewer checklist), pointing the author at this ADR. It is a **soft warn, not a hard block** — destructive
DDL is legitimate in a contract (N+1) release; the guard exists to make the expand/contract decision a
*conscious, reviewed* one, not to forbid it.

**Consequences:** The discipline is owed now (Phase 8), not deferred to Phase 9; it is therefore decoupled
from whether the blue/green machinery is ever built. Reviewers and the soft guard carry it. The cost is a
two-release dance for destructive schema changes (acceptable; standard for zero-downtime systems).

## ADR-0026 — Phase 10-1: least-privilege runtime DB role (RDS IAM auth) + the audit REVOKE
**Status:** Accepted. **Two-stage rollout** (see Rollout) so the live cutover carries no cold-boot risk.
**Note on numbering:** the assistant track's in-flight provenance ADR (drafted as a standalone
`docs/ADR-0024-...` file) must be renumbered to **ADR-0027** when it merges — this inline registry is the
single source of truth and 0026 is taken here.

**Context:** `audit.audit_events` is protected by the V8 `BEFORE UPDATE/DELETE` immutability trigger — but
the app connects as the **RDS master user** (`eopadmin`, injected from the RDS-managed secret), which *owns*
the schema and could drop the trigger and rewrite history. The trigger is belt; the real guarantee is a
runtime role that structurally lacks the privilege. (This closes the "role REVOKE rides Phase 10" note left
in V8.)

**Decision — split "migrate" from "run" (standard two-role pattern):**
- **Migrator = the RDS master.** Flyway keeps connecting as `eopadmin` (it needs DDL + role admin). Migrations
  run as master.
- **Runtime = `eop_app`** (Flyway V10 creates it, idempotently). Grants: `SELECT` on all app schemas;
  `INSERT/UPDATE/DELETE` on the **mutable** schemas (`messaging, request, platform, access, teams, notify`);
  **`INSERT`-only on `audit.audit_events`** with an explicit `REVOKE UPDATE, DELETE, TRUNCATE` (defense in
  depth over by-omission). `ALTER DEFAULT PRIVILEGES` sets the same defaults for **future** tables/sequences
  so a new migration can't accidentally lock the app out of its own new table (ties to ADR-0025). Coverage is
  **repo-wide**: the USAGE grant + both default-privilege blocks include the reserved empty schemas
  (`directory`, `onboarding`, `registry`) too — so the "privilege-safe by default" claim holds even for the
  schema (`directory`, the Graph module) most likely to gain a table next; otherwise that table would work as
  master but break as `eop_app` after Stage 2.
- **Auth = RDS IAM database auth** (chosen over a second stored password): `eop_app` is `LOGIN` +
  `GRANT rds_iam` (guarded `IF EXISTS` so V10 also runs on vanilla Postgres in CI/Testcontainers); the ECS
  **task role** gets `rds-db:connect` scoped to `dbuid:<DbiResourceId>/eop_app`; the instance gets
  `iam_database_authentication_enabled = true`. **No stored DB password for the runtime path** — on-theme
  with the project's WIF/OIDC zero-stored-credentials identity ("even the DB login is credential-less"). The
  app uses the AWS Advanced JDBC Wrapper (`iam` plugin) with Hikari `maxLifetime < 15m` for token refresh, and
  SSL (IAM auth mandates TLS). Flyway keeps a **separate** datasource on the master secret.

**Rollout (two stages — the cold-boot guard):** blue/green/rolling means old+new tasks share the DB, and the
runtime role must exist *before* any task connects as it.
- **Stage 1 (this PR + its gated apply — SAFE):** V10 creates `eop_app`; TF enables instance IAM auth + the
  task `rds-db:connect` policy. The **running app still connects as master** — Stage 1 only *adds* capability,
  so there is zero cold-boot risk. After apply, an IAM connection as `eop_app` can be smoke-tested (psql-runner
  pattern) to prove the role + IAM path before any cutover.
- **Stage 2 (separate PR — the cutover):** point `spring.datasource` at `eop_app`/IAM (Flyway stays master).
  Deployed only after Stage 1 is confirmed live. Hikari `initialization-fail-timeout=-1` keeps the first
  runtime connection lazy (after Flyway/migrator has run), which is the ordering that makes a cold boot safe.

**Guarded by** `LeastPrivRuntimeRoleTest` (Testcontainers): after V10, `has_table_privilege('eop_app', …)`
proves INSERT+SELECT-but-not-UPDATE/DELETE on audit, and writable mutable schemas. The IAM-token path itself
is only exercisable against real RDS (verified post-apply), not in CI.

**Consequences:** the audit table verified single-writer + hash-chained (Phase 6a / #162) is now append-only
at the **engine** level too — the app cannot rewrite history regardless of the trigger. Cost: a JDBC-wrapper
dependency + token-refresh tuning (Stage 2), and the two-stage rollout discipline.

**Stage 2 update (2026-07-01) — shipped with a managed PASSWORD, not IAM-token, login (#175).** Stage 1 went
live and was verified (role, grants, audit REVOKE, RDS IAM-auth flag, task `rds-db:connect` — all confirmed on
the real RDS). But the IAM **token login** for `eop_app` fails with RDS `PAM authenticate failed: Permission
denied`, despite an exact-matching `rds-db:connect` policy, a well-formed token signed by `eop-dev-task`, no
permissions boundary, engine IAM-readiness (pg_hba `+rds_iam` rule matched), and >1h elapsed (not propagation);
the IAM policy simulator can't even model `rds-db:connect` (false-negative, proven via `simulate-custom-policy`).
Per a time-boxed 3-check diagnosis (exact error ✓, `rds_iam` membership ✓, real token+TLS ✓ — none the culprit),
we took the architect's **fallback**: `eop_app` authenticates with a **managed password** (V11 `ALTER ROLE …
PASSWORD` fed from a CMK-encrypted Secrets Manager secret via a Flyway placeholder; the app datasource reads the
same secret; Flyway stays master). **Security posture is identical** — the least-privilege role + audit REVOKE
(the actual goal) are unchanged; only the login mechanism differs (a managed secret, exactly like the RDS master
user — not a narrative regression). The IAM-auth infra (rds_iam grant, instance flag, task `rds-db:connect`) is
**left in place** — harmless when unused — so IAM can be retried later with zero TF change if the root cause is
found. The JDBC-wrapper dependency is therefore **not** needed. `initialization-fail-timeout=-1` keeps the
runtime pool's first connect lazy (after Flyway/master has run V11), preserving cold-boot safety.
