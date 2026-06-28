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
