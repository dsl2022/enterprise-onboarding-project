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
