# Bootstrap — Azure CI identity (Phase 3)

Self-contained Terraform for the **GitHub → Azure (Entra) CI app** that manages the
workload app registration from Phase 3 onward. Own **local state**, separate from
the core bootstrap (`../bootstrap`) and the main stack (`../`): `terraform destroy`
here removes **only** the Entra CI app + its GitHub secrets.

Replaces `RUNBOOK.md` section **3**.

## What it manages

- `eop-github-ci` Entra **application** + **service principal** (no client secret)
- **3 federated identity credentials** — one per GitHub subject (`pull_request`,
  `environment:dev`, `ref:refs/heads/main`), same multi-subject pattern as the AWS roles
- **Graph `Application.ReadWrite.All`** app permission + **admin consent**
  (via `azuread_app_role_assignment` — the Terraform equivalent of
  `az ad app permission admin-consent`). Needed so CI can create the workload app's
  **service principal** and **federated credential** (Phase 3); `Application.ReadWrite.OwnedBy`
  is insufficient for service-principal creation.
- GitHub secrets **`AZURE_CLIENT_ID`** / **`AZURE_TENANT_ID`** (toggle `manage_github_secrets`)

**Stays manual:** §4 consent for the *workload* app (created by the main stack in
Phase 3; its client id only exists post-apply and consent needs a human Global Admin).

## Run it (Phase 3, tenant admin)

```bash
cd deploy/terraform/bootstrap-azure

az login                          # as a Global / Privileged Role Admin of the tenant
export GITHUB_TOKEN=$(gh auth token)

terraform init
terraform apply                   # defaults: dsl2022/enterprise-onboarding-project, dev
```

Admin consent (`azuread_app_role_assignment`) requires your `az login` identity to
hold a role that can grant app-role assignments (Global Administrator or Privileged
Role Administrator). If it doesn't, that one resource will fail — apply the rest and
have an admin consent separately.

## Teardown — destroys ONLY this layer

```bash
terraform destroy
```

## Notes

- **azuread** pinned to `~> 2.53`. The provider warns that `application_id` is
  deprecated in favor of `client_id` (v3); harmless on 2.x. Bump the pin + switch
  to `client_id` / `application_id` (on the FIC) when you move to azuread v3.
- **Local state** (git-ignored): holds object ids, no secrets.
