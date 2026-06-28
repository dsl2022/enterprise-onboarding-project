# enterprise-onboarding-project — v0 cross-cloud walking skeleton

Proves the hardest seam first: a user logging into an **AWS-hosted** app via **Microsoft Entra ID**,
and that app calling **real Microsoft Graph** across clouds using **Workload Identity Federation
(WIF) with no stored credential**.

- **Flow 1 — User SSO**: Spring Boot BFF, OIDC Auth Code + PKCE against Entra. Tokens stay server-side.
- **Flow 2 — App-only, cross-cloud, no stored secret**: ECS Fargate mints a short-lived JWT signed by
  our own AWS-hosted OIDC issuer (S3 + CloudFront), exchanges it at Entra for a Graph app-only token via
  a **federated identity credential**, and calls `GET /v1.0/groups`.

**All Terraform runs in GitHub Actions — never locally.** PR → plan (comment) → `dev` environment
approval → apply. See [`PROJECT_BRIEF_V0.md`](PROJECT_BRIEF_V0.md) (source of truth),
[`RUNBOOK.md`](RUNBOOK.md) (one-time bootstrap, admin consent, key rotation, teardown), and
[`DECISIONS.md`](DECISIONS.md) (ADRs).

## Layout
```
app/                      Spring Boot 3 (Java 21): auth / wif / graph packages + throwaway UI
Dockerfile                Multi-stage (maven:temurin-21 build -> temurin JRE runtime)
deploy/terraform/         Root module + envs/ + modules/ (godaddy-derived conventions)
.github/workflows/        ci / infra (plan+apply) / infra-destroy / app-deploy
```

## Conventions (inherited from the godaddy project, adapted)
| Thing | Value |
|---|---|
| project / prefix | `enterprise-onboarding` / `eop-dev` |
| region | `us-east-1` |
| TF state bucket / lock | `eop-tfstate-dev-dsl2022` / `eop-tflock` |
| state key | `enterprise-onboarding/dev/terraform.tfstate` |
| ECR repo | `eop-dev-app` |
| GitHub→AWS roles | `eop-gha-plan` / `eop-gha-apply` / `eop-gha-deploy` (OIDC, no keys) |
| GitHub→Azure | `eop-github-ci` Entra app (federated creds, no secret) |

## Local dev (optional — CI is authoritative)
```
docker build -t eop-app .
docker run --rm -p 8080:8080 eop-app
curl localhost:8080/healthz
```

## Demo / proof (v0 done)
App URL: `https://d3919zy3gh57yu.cloudfront.net` (front-door CloudFront → ALB → Fargate).
Workload OIDC issuer: `https://d1an4lciob7kqw.cloudfront.net` (S3 + CloudFront).

1. **Sign in with Microsoft** → Entra OIDC (Auth Code + PKCE), server-side session. `/auth/me` shows the user.
2. **List my groups** → `/api/graph/groups` returns real Graph groups, e.g.:
   ```json
   { "count": 3, "groups": [
     { "displayName": "App-Owners" }, { "displayName": "Auditors" }, { "displayName": "SSO-Operations" } ] }
   ```

The cross-cloud chain, from CloudWatch (`/eop-dev/app`) — **no stored credential, no token material logged**:
```
IssuerKeyService   Generated signing key kid=iqDhix…, stored in Secrets Manager
IssuerPublisher    Published JWKS to s3://eop-dev-issuer-…/.well-known/jwks.json (served via CloudFront)
WifAssertionService WIF exchange OK: obtained Graph app-only token (expires 3599s)
GraphService       Graph /groups returned 3 groups across 1 page
```
The AWS app authenticated to Entra purely by signing a JWT with its own issuer key (public half in the
published JWKS) — Entra matched it against the federated identity credential (issuer + subject +
audience). The only stored secret is the Flow 1 login client secret; **Flow 2 uses none**.

Definition of done: ✅ Flow 1 SSO · ✅ Flow 2 Graph via WIF (no stored credential) · ✅ RP-initiated
logout · ✅ all Terraform via CI (PR → plan → `dev` approval → apply), never locally · ✅ `infra-destroy`
tears everything down (see RUNBOOK §6).
