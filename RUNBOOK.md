# RUNBOOK — enterprise-onboarding-project (v0)

One-time bootstrap (browser Cloud Shells, **no local laptop steps**), then everything is CI-driven.
Nothing here stores a static cloud credential. Run the sections in order.

**Order of operations**
1. [AWS CloudShell](#1-aws-cloudshell--state-backend--githubaws-oidc) — state backend + GitHub→AWS OIDC + 3 roles.
2. [Set GitHub repo secrets + the `dev` Environment](#2-github-repo-secrets--dev-environment).
3. [Azure Cloud Shell](#3-azure-cloud-shell--githubazure-ci-app) — GitHub→Azure CI app (needed from **Phase 3**, can run now).
4. Open/merge PRs → plan → approve `dev` → apply.
5. [Phase 3 admin consent](#4-phase-3-admin-consent-human-global-admin) (after Terraform creates the Entra app).
6. [Key rotation](#5-issuer-signing-key-rotation-phase-2) / [Teardown](#6-teardown).

> **Automated path (preferred):** sections **1–3** are now Terraform. Run
> [`deploy/terraform/bootstrap`](deploy/terraform/bootstrap) (AWS state backend +
> OIDC roles + GitHub secrets/env) and, at Phase 3,
> [`deploy/terraform/bootstrap-azure`](deploy/terraform/bootstrap-azure) (Entra CI
> app). Each is a self-contained, local-state module that destroys on its own — see
> their READMEs. The manual CLI below is kept as reference / a no-Terraform fallback.

Shared values used below:
```bash
export GH_OWNER=dsl2022
export GH_REPO=enterprise-onboarding-project
export AWS_REGION=us-east-1
export ENVNAME=dev
export STATE_BUCKET=eop-tfstate-dev-dsl2022
export LOCK_TABLE=eop-tflock
export TENANT_ID=5b7b1ae8-bcc5-485f-ad46-cb099090670e
export SUB_REPO="repo:${GH_OWNER}/${GH_REPO}"
```

---

## 1. AWS CloudShell — state backend + GitHub→AWS OIDC

Open **AWS CloudShell** in the target account/region (`us-east-1`). Paste the block above first, then:

### 1a. Remote state (S3 + DynamoDB)
```bash
# State bucket (versioned, encrypted, private, TLS-only)
aws s3api create-bucket --bucket "$STATE_BUCKET" --region "$AWS_REGION"
aws s3api put-bucket-versioning --bucket "$STATE_BUCKET" \
  --versioning-configuration Status=Enabled
aws s3api put-bucket-encryption --bucket "$STATE_BUCKET" \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"aws:kms"}}]}'
aws s3api put-public-access-block --bucket "$STATE_BUCKET" \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-policy --bucket "$STATE_BUCKET" --policy "$(cat <<JSON
{"Version":"2012-10-17","Statement":[{"Sid":"DenyInsecureTransport","Effect":"Deny","Principal":"*","Action":"s3:*","Resource":["arn:aws:s3:::${STATE_BUCKET}","arn:aws:s3:::${STATE_BUCKET}/*"],"Condition":{"Bool":{"aws:SecureTransport":"false"}}}]}
JSON
)"

# Lock table
aws dynamodb create-table --table-name "$LOCK_TABLE" \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST --region "$AWS_REGION"
```

### 1b. GitHub OIDC identity provider (account-wide singleton)
```bash
# Skip if it already exists in this account.
aws iam create-open-id-connect-provider \
  --url "https://token.actions.githubusercontent.com" \
  --client-id-list "sts.amazonaws.com" \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1 1c58a3a8518e8759bf075b76b750d4f2df264fcd

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
OIDC_ARN="arn:aws:iam::${ACCOUNT_ID}:oidc-provider/token.actions.githubusercontent.com"
echo "OIDC_ARN=$OIDC_ARN"
```

### 1c. Three roles, each pinned to a different `sub` (the trigger-specific subject)
```bash
mktrust () { # $1 = sub value
cat <<JSON
{"Version":"2012-10-17","Statement":[{"Effect":"Allow",
"Principal":{"Federated":"${OIDC_ARN}"},
"Action":"sts:AssumeRoleWithWebIdentity",
"Condition":{"StringEquals":{"token.actions.githubusercontent.com:aud":"sts.amazonaws.com",
"token.actions.githubusercontent.com:sub":"$1"}}}]}
JSON
}

# PLAN role  — pull requests, read-only + state backend access
aws iam create-role --role-name eop-gha-plan \
  --assume-role-policy-document "$(mktrust "${SUB_REPO}:pull_request")"
aws iam attach-role-policy --role-name eop-gha-plan \
  --policy-arn arn:aws:iam::aws:policy/ReadOnlyAccess
aws iam put-role-policy --role-name eop-gha-plan --policy-name tf-backend \
  --policy-document "$(cat <<JSON
{"Version":"2012-10-17","Statement":[
{"Effect":"Allow","Action":["s3:ListBucket","s3:GetObject","s3:PutObject"],"Resource":["arn:aws:s3:::${STATE_BUCKET}","arn:aws:s3:::${STATE_BUCKET}/*"]},
{"Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:PutItem","dynamodb:DeleteItem"],"Resource":"arn:aws:dynamodb:${AWS_REGION}:${ACCOUNT_ID}:table/${LOCK_TABLE}"}]}
JSON
)"

# APPLY role — gated by the `dev` GitHub Environment (sub = ...:environment:dev)
aws iam create-role --role-name eop-gha-apply \
  --assume-role-policy-document "$(mktrust "${SUB_REPO}:environment:${ENVNAME}")"
aws iam attach-role-policy --role-name eop-gha-apply \
  --policy-arn arn:aws:iam::aws:policy/AdministratorAccess   # TODO: scope down post-v0

# DEPLOY role — app build/push on main (sub = ...:ref:refs/heads/main)
aws iam create-role --role-name eop-gha-deploy \
  --assume-role-policy-document "$(mktrust "${SUB_REPO}:ref:refs/heads/main")"
aws iam attach-role-policy --role-name eop-gha-deploy \
  --policy-arn arn:aws:iam::aws:policy/AdministratorAccess   # TODO: scope to ECR+SSM+(Phase4 ECS) post-v0

# Print the ARNs you'll register as GitHub secrets in section 2:
for r in eop-gha-plan eop-gha-apply eop-gha-deploy; do
  echo "$r = $(aws iam get-role --role-name $r --query Role.Arn --output text)"
done
```

> **Why three roles / three subjects:** GitHub's OIDC token `sub` differs by trigger — a PR run carries
> `...:pull_request`, an environment-gated job carries `...:environment:dev`, a main-branch job carries
> `...:ref:refs/heads/main`. Each role trusts exactly one, so a PR can never assume the apply role. This
> is the *same federated-credential idea* you'll use for the workload→Entra exchange in Flow 2 — a warm-up rep.

---

## 2. GitHub repo secrets + `dev` Environment

Run locally (you're authed as `dsl2022`) or in any shell with `gh`. Paste the ARNs from 1c:
```bash
gh secret set AWS_PLAN_ROLE_ARN   -R "$GH_OWNER/$GH_REPO" --body "arn:aws:iam::<ACCT>:role/eop-gha-plan"
gh secret set AWS_APPLY_ROLE_ARN  -R "$GH_OWNER/$GH_REPO" --body "arn:aws:iam::<ACCT>:role/eop-gha-apply"
gh secret set AWS_DEPLOY_ROLE_ARN -R "$GH_OWNER/$GH_REPO" --body "arn:aws:iam::<ACCT>:role/eop-gha-deploy"
```

Create the **`dev` Environment with a required reviewer** (this approval gate replaces "ask before apply"):
```bash
# UI: Repo > Settings > Environments > New environment "dev" > Required reviewers > add yourself.
# Or via API (adds yourself as a required reviewer):
MY_ID=$(gh api user --jq .id)
gh api -X PUT "repos/$GH_OWNER/$GH_REPO/environments/dev" \
  -f "reviewers[][type]=User" -F "reviewers[][id]=$MY_ID" >/dev/null
echo "dev environment created with you as required reviewer"
```
> No static cloud secrets are stored — only role ARNs (public identifiers).

---

## 3. Azure Cloud Shell — GitHub→Azure CI app

Needed from **Phase 3** (lets CI manage the Entra app registration). Safe to run now. Open **Azure Cloud
Shell** (Bash) signed in as Global Admin of tenant `$TENANT_ID`. Paste the shared values block, then:

```bash
GH_ISSUER="https://token.actions.githubusercontent.com"

# CI app + service principal (no secret is ever created)
APP_ID=$(az ad app create --display-name "eop-github-ci" --query appId -o tsv)
az ad sp create --id "$APP_ID" >/dev/null
echo "AZURE_CLIENT_ID=$APP_ID"

# Federated credentials — one per GitHub subject (same multi-subject gotcha as AWS)
addfic () { # $1 = name  $2 = subject
  az ad app federated-credential create --id "$APP_ID" --parameters "$(cat <<JSON
{"name":"$1","issuer":"${GH_ISSUER}","subject":"$2","audiences":["api://AzureADTokenExchange"]}
JSON
)"
}
addfic gh-pr  "${SUB_REPO}:pull_request"
addfic gh-dev "${SUB_REPO}:environment:${ENVNAME}"
addfic gh-main "${SUB_REPO}:ref:refs/heads/main"

# Grant the CI app rights to manage app registrations it owns (Microsoft Graph app permission
# Application.ReadWrite.OwnedBy = 18a4783c-866b-4cc7-a460-3d5e5662c884), then admin-consent.
az ad app permission add --id "$APP_ID" \
  --api 00000003-0000-0000-c000-000000000000 \
  --api-permissions 18a4783c-866b-4cc7-a460-3d5e5662c884=Role
az ad app permission admin-consent --id "$APP_ID"
echo "AZURE_TENANT_ID=$TENANT_ID"
```

Register the Azure CI identifiers as GitHub secrets (consumed by `azure/login` starting Phase 3):
```bash
gh secret set AZURE_CLIENT_ID -R "$GH_OWNER/$GH_REPO" --body "$APP_ID"
gh secret set AZURE_TENANT_ID -R "$GH_OWNER/$GH_REPO" --body "$TENANT_ID"
```
> `azure/login` will run with `allow-no-subscriptions: true` — we touch only directory objects, no
> subscription (ADR-0003). No client secret anywhere.

---

## 4. Phase 3 admin consent (human / Global Admin)

The **application** Graph permission `Group.Read.All` on the *app we deploy* requires tenant admin
consent — and the app's **client id only exists after Terraform creates it** in Phase 3. After that
apply, Terraform outputs `entra_app_client_id`; then run (Azure Cloud Shell):
```bash
az ad app permission admin-consent --id "<entra_app_client_id-from-terraform-output>"

# Verify the service principal actually holds the app role before declaring Flow 2 ready:
SP_ID=$(az ad sp show --id "<entra_app_client_id>" --query id -o tsv)
az rest --method GET \
  --url "https://graph.microsoft.com/v1.0/servicePrincipals/$SP_ID/appRoleAssignments" \
  --query "value[].appRoleId"
```
> **Delegated vs application:** Flow 1 uses *delegated* scopes (act as the signed-in user). Flow 2 is
> *application* permission (the app acts as itself) — those always require admin consent, and
> `/.default` at the token endpoint means "every statically-consented app permission for this client".

---

## 5. Issuer signing-key rotation (Phase 2)

The workload OIDC issuer's RSA key lives **only** in Secrets Manager (`eop-dev/issuer-signing-key`,
CMK-encrypted). The app generates it on first boot and publishes the public half to
`<issuer_url>/.well-known/jwks.json`. Workload assertions are short-lived (minutes), so rotation is
simple:
```bash
# 1) Clear the stored key so the app regenerates on next start (Secrets Manager keeps the prior
#    version as AWSPREVIOUS, so you can roll back):
aws secretsmanager put-secret-value --secret-id eop-dev/issuer-signing-key \
  --secret-string '{}'   # any non-key value; app treats unparbleable/empty as "regenerate"
# 2) Restart the ECS service so a task boots, generates a fresh key (new kid), and overwrites jwks.json:
aws ecs update-service --cluster eop-dev --service eop-dev --force-new-deployment
# 3) CloudFront serves the issuer with Managed-CachingDisabled, so the new JWKS is visible immediately.
#    Old assertions expire within minutes; no dual-publish needed at v0's single-key scale.
```
> A zero-downtime multi-key rotation (publish new + old, switch active `kid`, drop old) is the upgrade
> path if assertion lifetimes ever grow; not needed for v0.

## 6. Teardown

Per-environment infra (between sessions, for cost):
```
GitHub > Actions > infra-destroy > Run workflow > env=dev, confirm=destroy-dev  (approve `dev` env)
```
This removes the whole main stack (VPC/NAT, ALB, both CloudFront distros, ECS, ECR images, secrets,
issuer bucket incl. the app-published jwks.json, **and the Phase 2 RDS Postgres + ElastiCache Redis**).
The RDS-managed master secret deletes with the instance; `skip_final_snapshot`/no Redis final snapshot
keep it clean. Two expected, harmless residues:
- the **KMS CMK** is *scheduled* for deletion over AWS's mandatory 7-day window (can't be immediate),
- the **SSM param `/eop/dev/app_image`** (written by app-deploy, not in TF state) survives — free; delete with:
  ```bash
  aws ssm delete-parameter --name /eop/dev/app_image
  ```
Bootstrap leftovers (only when retiring the project entirely — these are cheap/free to keep):

If you created them via Terraform (preferred), just destroy each module — independent, any order:
```bash
terraform -chdir=deploy/terraform/bootstrap-azure destroy   # Entra CI app + AZURE_* secrets (if applied)
terraform -chdir=deploy/terraform/bootstrap destroy         # state bucket, lock table, roles, GH secrets/env
# Note: the shared GitHub OIDC provider is referenced, not owned — destroy leaves it in place.
```

Manual fallback (only if you used the CLI path / lost the bootstrap local state):
```bash
# AWS: roles, OIDC provider, lock table, then the versioned state bucket
for r in eop-gha-plan eop-gha-apply eop-gha-deploy; do
  for p in $(aws iam list-attached-role-policies --role-name $r --query 'AttachedPolicies[].PolicyArn' --output text); do
    aws iam detach-role-policy --role-name $r --policy-arn $p; done
  for p in $(aws iam list-role-policies --role-name $r --query 'PolicyNames' --output text); do
    aws iam delete-role-policy --role-name $r --policy-name $p; done
  aws iam delete-role --role-name $r
done
aws dynamodb delete-table --table-name "$LOCK_TABLE"
aws s3 rb "s3://$STATE_BUCKET" --force
# Azure: remove the CI app
az ad app delete --id "$(az ad app list --display-name eop-github-ci --query '[0].appId' -o tsv)"
```

## 7. Phase 2 data layer (RDS Postgres + Redis) — operating notes

**No human consent or out-of-band step is required for Phase 2.** It is pure AWS infra + app wiring;
the Entra/Graph consents are Phases 4–5. Deploy follows the normal spine:

1. Merge the Phase 2 PR (CI green: `test` job = Testcontainers PG+pgvector & Redis + ArchUnit;
   `terraform` fmt/validate; `app` image build; `openapi` unchanged).
2. `app-deploy` builds+pushes the new image (data-profile code) and writes `/eop/dev/app_image`.
3. Run **`infra`** (workflow_dispatch, env=dev) and approve. TF creates RDS first (waits ~10 min for
   `available`), then ElastiCache, then rolls the ECS task def with `SPRING_PROFILES_ACTIVE=auth,data`.

**Verify after apply:**
```bash
terraform -chdir=deploy/terraform output db_endpoint redis_endpoint
# App logs should show Flyway applying V1__baseline (creates `vector` extension + per-module schemas):
aws logs tail /eop-dev/app --since 15m --filter-pattern "Flyway"
```
- **First-boot race:** if a task starts before RDS is `available`, Flyway retries (≤10 min) and the 300s
  health-check grace prevents the LB from killing it. It self-heals; no action needed.
- **Cost:** `db.t4g.micro` + `cache.t4g.micro` ≈ a few $/day. Tear down between sessions via §6.
- **Redis is a single node in dev = a session SPOF, not HA.** Losing it drops active BFF sessions
  (users re-login). Set `-var multi_az=true` for a replica + automatic failover in prod (also flips the
  client to `rediss://` when transit encryption is enabled).
- **DB credentials** live only in the RDS-managed secret (CMK-encrypted); the task injects
  `username`/`password` from it. Nothing to rotate manually in dev.

## 8. Phase 3a — portal app roles + per-role login (RBAC seed)

Phase 3a declares the **6 portal app roles** on `eop-dev-app` and seeds **app-role assignments** for
test users. No Graph consent is needed (app roles are app-local, not Graph permissions). Order matters
to avoid locking anyone out.

> **Baseline role = `APPLICATION_OWNER`, NOT `READ_ONLY`.** `access.request` is granted only to
> `APPLICATION_OWNER`/`SSO_OPERATIONS`/`ADMIN`/`SUPER_ADMIN` — so a self-serving employee whose baseline
> is `READ_ONLY` (or no role) is **all-403 on requests**. There is no lean "requester/employee" role in
> the 6-role model (a 7th role would be a Phase-5 CR); `APPLICATION_OWNER` is the correct baseline.

**1. Get each test user's object id** (objectIds aren't secret):
```bash
az ad user show --id testuser@job2019tmmgmail.onmicrosoft.com --query id -o tsv
az ad user show --id job2019tmm@gmail.com --query id -o tsv   # your GA login — must also get a role
```

**2. Fill `deploy/terraform/envs/dev.tfvars`** → `entra_app_role_assignments` (one row per role; repeat a
user across two rows for a multi-role union demo). Assign **every account that signs in interactively**,
including your GA account, or `require=true` (step 4) will block its login.

**3. Apply with assignments, `require` still false** (merge the 3a PR → run `infra`, env=dev, approve):
```bash
terraform -chdir=deploy/terraform output app_role_ids   # verify the 6 roles exist with stable ids
```
Sign in as each test user and hit `/api/v1/me` — confirm `roles[]`, the display `role`, and (for the
Super Admin) `POST /api/v1/impersonation {"role":"READ_ONLY"}` then `GET /me` shows the reduced view +
`impersonating.role`, and `DELETE /impersonation` restores it.

**4. Flip enforcement on** (only after step 3 verifies every login has a role): set
`entra_require_app_role_assignment = true` in `dev.tfvars`, re-run `infra` + approve. Now only assigned
users can sign in. (Flow-2 WIF/Graph is app-only and unaffected throughout.)

**Rollback if a login breaks:** set `entra_require_app_role_assignment = false`, re-apply — sign-in opens
back up immediately while you fix the missing assignment.

## 9. Phase 4 — onboarding + app-registration provisioning

**4a (merged): no consent or infra step.** Pure app + DB migration (`V4` idempotency). The onboarding
workflow runs end to end with `eop.provisioning.simulate=true` (default) — `POST /applications` →
`/submit` → `/decision` (APPROVE) → the worker takes it to ACTIVE with a synthetic `sim-<id>` client id.
The provisioning scheduler is **off by default** (`eop.provisioning.scheduler` unset).

**4b (real Graph provisioning) — human GA consent required. Order is load-bearing: consent MUST precede
the token mint** — the WIF `.default` token bakes in consented permissions at mint time and is cached, so
a task that mints before consent lands will 403 until the cache expires. So: declare → consent → THEN
flip the flag and roll. The flag flip is gated behind the `provisioning_real` TF var (default `false`),
so the first apply only declares the permission.

1. **Declare + apply (flag still off).** TF `modules/entra` adds `Application.ReadWrite.OwnedBy` to
   `eop-dev-app`; `provisioning_real=false` keeps the SimulatedProvisioner in charge. Merge the 4b PR →
   run `infra` (env=dev, approve).
2. **Grant admin consent (Global Admin)** — Terraform/CI can't:
   ```bash
   # Resolve the Graph app-role GUID live (never hardcode); confirm it matches the TF default
   # (app_readwrite_ownedby_role_id = 18a4783c-866b-4cc7-a460-3d5e5662c884), then consent.
   az ad sp show --id 00000003-0000-0000-c000-000000000000 \
     --query "appRoles[?value=='Application.ReadWrite.OwnedBy'].id" -o tsv
   ```
   The Cloud-Shell `az ad app permission admin-consent` MSI-audience bug applies (AS-BUILT §5) — use the
   Portal "Grant admin consent" button, run `az` locally, or `az rest` against `appRoleAssignments`.
   Verify the SP actually holds the role before flipping the flag:
   ```bash
   az ad sp show --id <entra_app_client_id> --query "appRoles" -o table   # or check oauth2PermissionGrants/appRoleAssignments
   ```
3. **Flip on + roll.** Set `onboarding_provisioning_real = true` in `dev.tfvars`, re-run `infra` + approve
   (this sets `EOP_PROVISIONING_ONBOARDING_SIMULATE=false` on the task and rolls it). The GraphProvisioner
   also requires `WIF_ENABLED=true` (already set) since it mints over the WIF token.

   > **Per-vertical flags (why):** `simulate` is split — `eop.provisioning.onboarding.simulate` and
   > `eop.provisioning.access.simulate`. A single shared flag once crash-looped the task: flipping it to
   > false for 4b also disabled the **access** simulator, whose real impl is 5b, leaving
   > `AccessProvisioningService` with no `GroupMembershipProvisioner` → context init failed. So flip ONLY
   > the vertical whose real provisioner exists AND whose Graph permission is consented. The schedulers run
   > regardless (set in the service env), so a still-simulated vertical keeps completing.

**Verify at the first real apply (the parts only provable live — log them, CR-1416-item-3 style):**
- The `tags/any(t:t eq '…')` `$filter` on `/applications` is an **advanced query**: the provisioner sends
  `ConsistencyLevel: eventual` + `$count=true`. Confirm the find returns 200 (not a `$filter`-not-supported
  error) in the app logs.
- Confirm the **list** `GET /applications?$filter` is permitted under `OwnedBy` (not just `GET /{id}`).
- **Idempotency check:** onboard → approve → confirm an Entra app registration appears with the client id;
  then force a re-provision (e.g. clear `external_ref` or let the reaper fire) and confirm **no second app**
  is created (the tag find reuses the first). A duplicate would be logged as a `DUPLICATE … for tag` warning.

> **Scope limit — an onboarded app is NOT yet sign-in-capable.** `Application.ReadWrite.OwnedBy` creates
> the app **registration** (and returns the client ID) but **not a service principal**, so the onboarded
> app cannot be used for sign-in in the tenant until an SP is created (needs `Application.ReadWrite.All`
> or a portal step). This satisfies the contract DoD ("returns a client ID"); SP creation + secret
> minting + `secret.rotate` (and the `registry` module) are a deliberate fast-follow.

**Stuck-provisioning recovery (the reaper).** A request whose task dies mid-provision stays `PROVISIONING`;
the worker's reaper re-claims and re-provisions it once the lease (`EOP_PROVISIONING_LEASE_SECONDS`, default
300s — sized above worst-case provision duration) elapses, backing off exponentially (capped at
`EOP_PROVISIONING_BACKOFF_CAP_SECONDS`, default 3600s) so a permanently-failing request doesn't loop hot.
There is **no terminal `FAILED` state** yet (frozen enums) — a genuinely-stuck request retries forever at
the cap and emits a `provisioning_failed` event each cycle; ops surfaces it via that event / the rising
`provision_attempts`. A real "give up" terminal state is a future CR.
