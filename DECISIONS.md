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
