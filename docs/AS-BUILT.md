# AS-BUILT — enterprise-onboarding-project (v0)

As-built reference for the v0 walking skeleton: actual repo structure, naming
conventions, and the CI/CD shape as it landed. Intended as the stable reference
that v1 work builds on — **new modules/code should slot into these conventions,
not fight them.** Pairs with [DECISIONS.md](../DECISIONS.md) (the ADR spine),
[RUNBOOK.md](../RUNBOOK.md) (bootstrap/ops), and
[PROJECT_BRIEF_V0.md](../PROJECT_BRIEF_V0.md) (the original brief).

---

## 1. Repo structure & naming

### Directory tree (annotated)

```
enterprise-onboarding-project/
├── app/                                  # Java 21 / Spring Boot 3 BFF
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/eop/                 # root package = com.eop
│       │   ├── Application.java          # @SpringBootApplication
│       │   ├── auth/                     # Flow 1 — user SSO (delegated)
│       │   │   ├── AuthController.java   #   GET /auth/login, GET /auth/me
│       │   │   └── SecurityConfig.java
│       │   ├── graph/                    # Microsoft Graph call
│       │   │   ├── GraphController.java  #   @RequestMapping /api/graph → GET /groups
│       │   │   └── GraphService.java     #   RestClient, paging, 429 (ADR-0004)
│       │   ├── health/
│       │   │   └── HealthController.java #   GET /healthz
│       │   └── wif/                      # Flow 2 — workload identity federation
│       │       ├── AwsClientsConfig.java
│       │       ├── IssuerKeyService.java #   RSA key in Secrets Manager (ADR-0010)
│       │       ├── IssuerProperties.java #   @ConfigurationProperties("wif")
│       │       ├── IssuerPublisher.java  #   ApplicationRunner → publishes JWKS
│       │       └── WifAssertionService.java
│       └── resources/
│           ├── application.yml           # base
│           ├── application-auth.yml      # Flow 1 profile
│           └── static/index.html         # the throwaway one-page UI
├── deploy/terraform/
│   ├── backend.tf                        # partial S3 backend (filled at init)
│   ├── providers.tf  versions.tf  variables.tf  outputs.tf
│   ├── main.tf                           # ROOT = module wiring (the spine)
│   ├── envs/
│   │   ├── dev.backend.hcl               # bucket/key/region/lock for `init`
│   │   └── dev.tfvars                    # env_name/region/log_retention
│   ├── modules/                          # one concern per module, 4 files each
│   │   ├── platform/                     #   KMS CMK, ECR, log group  (Phase 1)
│   │   ├── issuer/                       #   S3+CloudFront OIDC issuer (Phase 2)
│   │   ├── network/                      #   VPC / 2 AZ / single NAT   (Phase 4)
│   │   ├── edge/                         #   ALB + CloudFront + SGs    (Phase 4)
│   │   ├── entra/                        #   azuread app+FIC+secret    (Phase 3)
│   │   └── service/                      #   ECS Fargate cluster/svc   (Phase 4)
│   ├── bootstrap/                        # self-contained, LOCAL state (AWS+GitHub)
│   └── bootstrap-azure/                  # self-contained, LOCAL state (Entra CI)
├── .github/workflows/                    # ci · infra · app-deploy · infra-destroy
├── DECISIONS.md  RUNBOOK.md  PROJECT_BRIEF_V0.md  README.md
└── Dockerfile  .dockerignore
```

> `app/target/` is Maven build output — generated, not source. If v1 touches the
> app, add it to `.gitignore`.

### Terraform module conventions (the rules v1 must follow)

- **Every module is exactly four files:** `main.tf`, `variables.tf`, `outputs.tf`,
  `versions.tf`. Match this for any v1 module.
- **One concern per module.** Modules never reach into each other; **all wiring
  happens in root `main.tf`** by passing one module's `outputs` into another's
  `variables`.
- **Every module takes `name_prefix`** and builds resource names from it.
  Cross-module dependencies are passed explicitly (e.g.
  `kms_key_arn = module.platform.kms_key_arn`), never re-derived.
- **Ordering via `depends_on`** only where an implicit edge isn't enough (see
  `edge` depending on the whole `network` module for the IGW).
- **Single state file** (ADR-0005) — no per-module state. A v1 module is just
  another `module ""` block in root `main.tf`.

### Naming in practice — the `eop` prefix

| Thing | Pattern | Concrete (dev) |
|---|---|---|
| TF name prefix | `eop-${env_name}` | `eop-dev` |
| AWS resources | `${name_prefix}-<thing>` | `eop-dev-app` (ECR), `eop-dev-workload` (subject) |
| Log group | `/${name_prefix}/<thing>` | `/eop-dev/app` |
| **SSM params** | `/eop/${env}/<thing>` | `/eop/dev/app_image` ⚠ note: `eop/dev` not `eop-dev` |
| Secrets Manager | `eop-*` | `eop-*` (issuer key, Flow 1 secret) |
| State bucket / lock | `eop-tfstate-<env>-<owner>` / `eop-tflock` | `eop-tfstate-dev-dsl2022` |
| IAM CI roles | `eop-gha-{plan,apply,deploy}` | — |
| Default tags (provider-level) | `Project / Env / ManagedBy` | `enterprise-onboarding / dev / terraform` |
| Java packages | `com.eop.<concern>` | `com.eop.auth`, `com.eop.wif` |

**The one inconsistency to know:** SSM uses `/eop/dev/…` (slash-delimited) while
everything else uses `eop-dev` (hyphen). The `name_prefix` local does *not*
produce the SSM path — it's hardcoded as `/eop/${ENV}/` in the workflows and the
app. The app and CI agree on it; don't "fix" it casually.

---

## 2. CI/CD shape as built

### Four workflows

| Workflow | Trigger | OIDC role / sub | Does |
|---|---|---|---|
| **ci.yml** | every PR + push to main | **none** (no creds) | `docker build` (ADR-0008) + `terraform fmt/validate` with `-backend=false` |
| **infra.yml** `plan` | PR touching `deploy/terraform/**` | `AWS_PLAN_ROLE_ARN` ← `:pull_request` | read-only plan, posts as PR comment |
| **infra.yml** `apply` | push to main / dispatch | `AWS_APPLY_ROLE_ARN` ← `:environment:dev` | **env-gated** apply (the guardrail) |
| **app-deploy.yml** | push to main touching `app/**` etc. | `AWS_DEPLOY_ROLE_ARN` ← `:ref:refs/heads/main` | build+push image, write SSM. **No Terraform.** |
| **infra-destroy.yml** | manual dispatch | `AWS_APPLY_ROLE_ARN` ← `:environment:dev` | env-gated `terraform destroy`, typed `destroy-dev` confirm |

### The deploy mechanic — image pointer via SSM (the spine v1 extends)

**`app-deploy` and `infra` are decoupled through an SSM parameter.** `app-deploy`
never runs Terraform; `infra` is the *sole* applier.

```
  push app/** to main
        │
        ▼
  app-deploy (deploy role, :ref:main)
   ├─ docker build → push  →  ECR: eop-dev-app:<git-sha>
   └─ aws ssm put-parameter /eop/dev/app_image = <ecr-uri>:<sha>
        │
        ▼  (handoff — no auto-trigger; deliberate)
  infra apply (apply role, :environment:dev, REQUIRES REVIEWER APPROVAL)
   ├─ IMAGE=$(aws ssm get-parameter /eop/dev/app_image)
   └─ terraform apply -var "app_image=$IMAGE"   →  ECS rolls the new task def
```

- Both **plan and apply read the SSM pointer** and pass it as `-var app_image=…`
  so the service isn't dropped when Terraform runs for unrelated reasons. Empty
  SSM falls back to `""`.
- **Why decoupled** (header comment in app-deploy): keeps the env approval gate as
  the only path to prod, and avoids `-target` partial-graph hazards. To roll a new
  image you **run `infra` and approve** — pushing app code alone only stages the image.
- **azuread in the main stack runs via OIDC too:** infra.yml exports
  `ARM_USE_OIDC / ARM_CLIENT_ID / ARM_TENANT_ID` from the `eop-github-ci` secrets,
  so the `entra` module applies with no Azure secret.

### The `dev` environment gate

A GitHub **Environment named `dev` with a required reviewer**. Only the `apply`
and `destroy` jobs declare `environment: dev`, which (a) pauses for approval and
(b) makes their OIDC `sub` become `:environment:dev` — matching only the apply
role. `app-deploy` deliberately has **no** environment so its `sub` stays
`:ref:refs/heads/main` (matching the deploy role).

### Bootstrap (the layer below CI)

Two **self-contained, local-state** Terraform modules, each independently
destroyable, run once by a human:

- **`bootstrap/`** — AWS state backend (S3+DynamoDB), the GitHub OIDC provider
  (referenced as data source, not owned), the three `eop-gha-*` roles, GitHub repo
  secrets + the `dev` environment.
- **`bootstrap-azure/`** — the `eop-github-ci` Entra app + 3 federated creds +
  Graph `Application.ReadWrite.All` consent + the `AZURE_*` secrets.

---

## 3. ADR list (the spine — one-liners)

Full text in [DECISIONS.md](../DECISIONS.md).

| ADR | Decision |
|---|---|
| 0001 | Reuse the godaddy CI/CD pattern verbatim; change prefix to `eop`, add Azure |
| 0002 | All `apply` in CI, env-gated; never local apply |
| 0003 | Azure is config-only → `azuread` provider only, **no `azurerm`/subscription** |
| 0004 | Call Graph via raw Spring `RestClient`, not the Graph Java SDK |
| 0005 | **Single state file**; order issuer (P2) before Entra FIC (P3) |
| 0006 | One Entra app registration for both flows in v0 |
| 0007 | CloudFront default `*.cloudfront.net` domain as issuer host + app URL |
| 0008 | CI builds image via Docker; no local Maven |
| 0009 | *(TODO/hardening)* pin Actions to commit SHAs; scope roles down from admin |
| 0010 | Issuer RSA key lives **only** in Secrets Manager; the app generates/owns it (never in TF state) |
| 0011 | CI Entra identity needs **`Application.ReadWrite.All`** (OwnedBy can't create SPs/FICs) |

---

## 4. What this means for v1 (how to slot in, not fight)

- **A v1 infra module** = a new `modules/<name>/` with the 4-file shape, taking
  `name_prefix` + explicit deps, wired in root `main.tf`. No new state, no new backend.
- **A v1 notifications path** most naturally becomes its own module (e.g.
  `modules/notify/` for SNS/SES/EventBridge) wired in root, with the app publishing
  via the **task role** (extend `module.service`'s IAM, as `issuer` does with
  `task_access_policy_arn`). New app code goes under `com.eop.<concern>` with a
  matching `application-<profile>.yml` if it needs gating (like `wif.enabled`).
- **Anything that ships a new image or artifact** should reuse the **SSM-pointer
  handoff**, not add Terraform to `app-deploy`. Follow `/eop/<env>/<name>` and read
  it as `-var` in infra.yml.
- **If v1 touches Azure**, it rides the existing `ARM_*` OIDC wiring and the
  `azuread`-only rule (ADR-0003); admin consent for *new* Graph permissions stays a
  human step (ADR-0011 / RUNBOOK §4).
- **Honor the deferred debt (ADR-0009)** when hardening: SHA-pin actions and scope
  the apply/deploy roles down from AdministratorAccess.

---

## 5. WIF / Entra wiring — gotchas & "do it this way"

Battle scars from building v0. Each bit (or was deliberately designed around) during this build —
follow these so v1 doesn't relearn them.

### Identity & permissions
- **CI identity needs `Application.ReadWrite.All`, not `Application.ReadWrite.OwnedBy`.** OwnedBy can
  create *app registrations* but **cannot create a service principal** (or, transitively, its federated
  credential) → Phase 3 apply died with `403 Authorization_RequestDenied`. (ADR-0011.) If v1 adds more
  directory automation, this role already covers it.
- **Admin consent is a human Global-Admin step — Terraform/CI can't do it.** Granting an app role
  assignment for Graph permissions needs privilege the CI app doesn't (and shouldn't) hold. Terraform
  *declares* `required_resource_access`; a human consents.
- **`az ad app permission admin-consent` fails in Azure Cloud Shell** with
  `Audience 74658136-... is not a supported MSI token audience`. Don't follow its `az logout/login`
  suggestion (breaks the shell session). Instead use any of: the **Portal** "Grant admin consent"
  button; run the command **locally** (user creds, not MSI); or `az rest --method POST .../servicePrincipals/<sp>/appRoleAssignments`
  (the `graph.microsoft.com` audience *is* MSI-supported).
- **Never hand-type Graph app-role GUIDs — resolve them live.** We pasted a wrong GUID once. Always:
  ```bash
  az ad sp show --id 00000003-0000-0000-c000-000000000000 \
    --query "appRoles[?value=='Group.Read.All' && allowedMemberTypes[0]=='Application'].id" -o tsv
  ```
  (Group.Read.All = `5b567255-7703-4780-807c-7be8301ae99b`; Application.ReadWrite.All = `1bfefb4e-e0b5-418b-a88f-73c46d2cc8e9`.)
- **Single-tenant app** (`AzureADMyOrg`): only tenant members can sign in. Test user UPN is
  `testuser@<tenant>.onmicrosoft.com` — the domain has no `@` in it, so paste the **full** UPN.

### The issuer (exact-match is the whole game)
- **`issuer` must be byte-for-byte `https://<cloudfront-domain>` — no trailing slash.** Three places must
  agree: the discovery doc's `issuer`, the minted assertion's `iss`, and the federated credential's
  `issuer`. We avoid drift by deriving all of them from **one Terraform source** (`module.issuer`
  output → app env `WIF_ISSUER_HOST` and `module.entra` FIC). A trailing-slash mismatch is the classic
  silent failure.
- **`subject` and `audience` likewise single-sourced.** FIC `subject` = app `WIF_SUBJECT` (both from
  the `local.workload_subject` = `eop-dev-workload`); `audience` = `api://AzureADTokenExchange`
  everywhere. The assertion's `aud` is that audience — **not** the token endpoint URL.
- **`kid` coherence is automatic via RFC-7638 thumbprint.** The app derives the JWK thumbprint as the
  `kid`, so the assertion header `kid` and the published JWKS `kid` always match — no manual ID to keep
  in sync.
- **JWKS goes live only after the app first boots** (the app owns/generates the key and publishes
  `jwks.json` — ADR-0010). Entra fetches it only at exchange time, so this is fine, but **Phase-2
  verification can only check the discovery doc over HTTPS**, not JWKS. CloudFront uses
  `Managed-CachingDisabled` so a rotated key propagates immediately.
- **No key-rotation automation and a single-task assumption.** Each task generates-or-loads one key;
  with >1 task you'd race on first generation. v1-at-scale needs a pre-provisioned key (or dual-key
  rotation). Same single-task caveat applies to the **in-memory session** and the **in-memory Graph
  token cache**.

### The exchange & Graph call
- **Client assertion:** RS256, header `kid` = signing key, claims `iss/sub/aud` matching the FIC, plus
  `iat/nbf/exp` (short, ~5 min) and `jti`. Token request: `grant_type=client_credentials`,
  `client_id=<app>`, `scope=https://graph.microsoft.com/.default`,
  `client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer`. **`.default`** = "all
  admin-consented application permissions for this client" (i.e. `Group.Read.All`).
- **Cache the Graph token** (~3599s `expires_in`); mint a fresh assertion per exchange. A `403` from
  Graph almost always means the app-only permission isn't admin-consented (not an auth-to-Entra
  problem). Handle `429` + `Retry-After`.

### App behind CloudFront (Flow 1 BFF)
- **Spring's OAuth2 callback path is `/login/oauth2/code/entra`** — register *that* as the redirect URI,
  not `/auth/callback`.
- **Real logout needs RP-initiated logout.** App logout only clears the local session; Entra SSO then
  silently re-logs the user in. Use `OidcClientInitiatedLogoutSuccessHandler` and **register the
  post-logout URL (`<app_url>/`) as a redirect URI too** — Entra validates `post_logout_redirect_uri`
  against registered redirect URIs.
- **CloudFront fronting a dynamic app:** `server.forward-headers-strategy=framework` (so Spring sees
  `X-Forwarded-Proto=https` → Secure cookies + correct redirect URIs), CloudFront
  `Managed-AllViewer` origin-request policy + `CachingDisabled`, and set **`APP_BASE_URL` explicitly**
  rather than relying on host detection. Note there are **two** CloudFront distributions — issuer vs
  app front door — don't confuse their domains.

### Terraform / CI specifics
- **`azuread` provider in CI authenticates via GitHub OIDC** with `ARM_USE_OIDC=true` +
  `ARM_CLIENT_ID` + `ARM_TENANT_ID` (no `azure/login` action needed). The CI app carries **one
  federated credential per GitHub subject** (`pull_request`, `environment:dev`, `ref:refs/heads/main`),
  mirroring the AWS roles.
- **`count`/`for_each` can't depend on after-apply values.** The Flow-1 secret-version `count` keyed on
  the (apply-time-unknown) client secret → `Invalid count argument`. Gate on a **static bool**, never
  the secret value.
- **The plan role needs secret read.** `terraform plan` refreshes secret-version resources, calling
  `secretsmanager:GetSecretValue` + `kms:Decrypt` — both **excluded** from AWS-managed `ReadOnlyAccess`.
  We added scoped grants to `eop-gha-plan` (bootstrap). Any v1 secret managed in TF inherits this.
- **`azuread` deprecation warnings** (`application_id` / `application_object_id`) are benign in
  `~> 2.53` but **will break at azuread 3.0** — migrate to `client_id` / `application_id` before bumping.
