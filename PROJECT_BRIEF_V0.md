# Master Build Prompt — v0 Cross-Cloud Walking Skeleton (CI-driven)

> This is the captured source of truth for the project. Resolved inputs (Phase 0) are recorded at the
> top; the original brief follows unchanged below.

## Resolved inputs (Phase 0, 2026-06-27)
- **Entra Tenant ID:** `5b7b1ae8-bcc5-485f-ad46-cb099090670e`
- **GitHub repo:** `dsl2022/enterprise-onboarding-project`
- **Issuer/ALB host:** CloudFront default domain (`*.cloudfront.net`) — zero cost, zero DNS
- **Environment:** `dev`
- **Admin consent:** run by the human (Global Admin) out-of-band, in Phase 3, once the app
  registration (and thus the app **client id**) exists. Exact command emitted into `RUNBOOK.md`.
- **Project prefix:** `enterprise-onboarding` / `eop-dev`
- **Azure scope decision:** Azure side is config-only (no Azure compute) → **`azuread` provider only,
  no `azurerm`, no subscription id**. CI Entra app uses `azure/login` with
  `allow-no-subscriptions: true`. See DECISIONS ADR-0003.
- **Graph access:** raw REST via Spring `RestClient` (not the Graph Java SDK). See ADR-0004.

---

## 0. Role & operating rules
1. First action — reuse the existing CI/CD at `/Users/dmml/interview-2026/godaddy` (Terraform + GitHub
   Actions, AWS OIDC, remote state, env gates, modules). Reuse/adapt that exact pattern; extend it with
   the Azure side (GitHub → Entra OIDC, `azuread` provider).
2. Restate the architecture, produce a phased plan, wait for go.
3. **All Terraform plan/apply runs in GitHub Actions — never `terraform apply` locally.** Plans on PR;
   applies on merge behind a required GitHub Environment approval. The approval gate is the guardrail.
4. **No static cloud credentials anywhere.** GitHub→AWS and GitHub→Azure are OIDC federation. No AWS
   access keys or Azure client secrets in the repo or in GitHub secrets.
5. Maintain `DECISIONS.md` (ADRs) and `RUNBOOK.md`.
6. Optimize for cost/minimalism — single region, one Fargate task, single NAT, no Redis. Provide a
   manual destroy workflow.

## 2. What v0 proves (and does NOT build)
Proves end-to-end, deployed via CI: Flow 1 (user SSO via Entra) and Flow 2 (app-only cross-cloud Graph
call via WIF, no stored credential). Does NOT build: onboarding/registry/authz/audit modules, AI
assistant, RBAC, audit store, multi-AZ HA.

## 3. The two flows
### Flow 1 — user login (delegated)
Spring Boot BFF confidential OIDC client. `/auth/login` → Entra `/authorize` (Auth Code + PKCE, scopes
`openid profile email User.Read`, `state`). `/auth/callback` validates state, exchanges code (+PKCE) at
`/token`, validates the ID token (JWKS sig, `iss`, `aud`, `exp`/`nbf`), creates a server-side session
(in-memory v0) keyed to an HttpOnly/Secure/SameSite cookie. Tokens never reach the browser. Code
exchange uses a client secret in AWS Secrets Manager (the one accepted stored secret; upgrade to a cert
later). `/auth/me` returns claims.

### Flow 2 — app-only via WIF
**3a. Workload OIDC issuer (AWS):** RSA keypair (private in Secrets Manager/KMS, readable by task role).
Publish over HTTPS via S3+CloudFront: `/.well-known/openid-configuration` whose `issuer` exactly equals
`https://<issuer-host>` and includes `jwks_uri`; `/.well-known/jwks.json` (`kid,kty=RSA,use=sig,n,e`).
At runtime mint a short-lived JWT: `iss=https://<issuer-host>`, `sub=<workload subject>`,
`aud=api://AzureADTokenExchange`, `iat/exp/jti`, RS256 with matching `kid`.

**3b. Exchange at Entra:**
```
POST https://login.microsoftonline.com/{TENANT_ID}/oauth2/v2.0/token
  grant_type=client_credentials
  client_id={APP_CLIENT_ID}
  scope=https://graph.microsoft.com/.default
  client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
  client_assertion={workload JWT from 3a}
```
Entra validates the assertion against the app's federated identity credential (issuer+subject+audience
all match) by fetching our discovery+JWKS, returns a Graph app-only token.

**3c. Call Graph:** `GET https://graph.microsoft.com/v1.0/groups?$select=id,displayName&$top=20`.
Follow `@odata.nextLink`; honor `429` + `Retry-After` (exp backoff); surface `403`; cache token until
near expiry. `/api/graph/groups` (active session required) triggers 3a→3c.

## 4. Azure side — configuration only (no compute)
Via Terraform `azuread`. One Entra app registration: Flow 1 (web redirect = `/auth/callback`, delegated
scopes, client secret in AWS Secrets Manager); Flow 2 (federated identity credential
issuer=`https://<issuer-host>`, subject=`<workload subject>`, audience=`api://AzureADTokenExchange`) +
application Graph permission `Group.Read.All` with admin consent (human runs it).

## 5. AWS side — infra (single region, cost-optimized)
VPC across 2 AZs, single NAT, private subnets for task, public for ALB. One ECS Fargate service behind
an ALB (HTTPS via ACM), image in ECR. Workload OIDC issuer hosting: private S3 + CloudFront (OAC).
Secrets Manager (issuer signing key, Flow 1 client secret) + KMS CMK. Least-privilege task role.
CloudWatch logs showing mint → Entra exchange (no token material) → Graph call with correlation id.

## 6. CI/CD model
OIDC federation, zero stored cloud secrets. GitHub→AWS: IAM OIDC provider, role trusted on
`sub=repo:OWNER/REPO:environment:<env>` (apply) and `:pull_request` (plan); `aud=sts.amazonaws.com`.
GitHub→Azure: CI Entra app with federated credentials trusting GitHub's issuer (subject per trigger,
audience `api://AzureADTokenExchange`), used via `azure/login`, no secret. Subject must match exactly
and differs by trigger → multiple federated credentials. Workflows: plan on PR (plan as comment); apply
on merge behind `<env>` environment approval; destroy (manual dispatch, env-gated). Remote state S3 +
DynamoDB lock. Default env `dev`.

## 7. Backend & frontend
Java 21 + Spring Boot 3.x. Endpoints: `/auth/login`, `/auth/callback`, `/auth/logout`, `/auth/me`,
`/api/graph/groups`, `/healthz`. Packages: `auth`, `wif`, `graph`. Throwaway one-page UI.

## 8. One-time bootstrap (browser Cloud Shell)
Copy-paste scripts in `RUNBOOK.md` for AWS CloudShell and Azure Cloud Shell to create: S3+DynamoDB
state backend; GitHub→AWS OIDC provider + CI roles; GitHub→Azure CI app + federated credentials.

## 9. Phased plan
0. Analyze godaddy + plan (DONE).
1. Bootstrap + skeleton app + workflows; prove `/healthz`.
2. Workload OIDC issuer (S3+CloudFront) deployed via CI.
3. Azure config (app registration, federated credential, Graph `Group.Read.All`); admin consent.
4. Flow 1 deployed (Fargate+ALB, real Entra login).
5. Flow 2 deployed (`/api/graph/groups` mint→exchange→Graph, pagination+429).
6. Proof + docs; confirm destroy leaves nothing behind.

## 10. Definition of done
A working ALB URL where the human can (a) sign in with Entra and see identity, (b) click "List my
groups" and get real Graph groups — AWS having authenticated to Entra via WIF with no stored credential
(only our issuer signing key). Logs show mint → exchange → Graph. Destroy removes everything. No
Terraform ever ran locally.

## 11. Learning checkpoints
Explain in plain language at each: CI OIDC setup (same federated-credential idea as workload→Entra);
after Flow 1 (what token represents the user, where it lives, why the browser never sees it); building
the issuer (why Entra needs public JWKS, what issuer/subject/audience matching enforces); first WIF
exchange (what proved identity without a shared secret); after Graph call (delegated vs application
perms, why admin consent, what `/.default` means); on 429/403 (throttling, missing app permission).
