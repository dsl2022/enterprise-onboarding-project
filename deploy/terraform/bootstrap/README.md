# Bootstrap (core) — self-contained, local-state Terraform

A **standalone** Terraform layer that creates everything the main stack + CI need
to exist *before* they can run. It keeps its **own local state** and is completely
separate from the main stack (`../`): a `terraform destroy` here removes **only
what this layer created** and can never touch the main stack's resources.

This replaces the manual CLI in `RUNBOOK.md` sections **1 and 2**. The Azure CI
identity (§3) lives in the sibling module [`../bootstrap-azure`](../bootstrap-azure)
— a separate state so it needs `az` only when you opt into it, and destroys on its
own. Each of the three layers (this, main stack, azure) is independently
destroyable.

## What it manages

| Layer | Resources | Toggle | RUNBOOK |
|---|---|---|---|
| **AWS state backend** | S3 state bucket (`eop-tfstate-<env>-<owner>`, versioned/encrypted/TLS-only) + DynamoDB lock table | always | §1a |
| **AWS OIDC + roles** | GitHub OIDC provider (referenced, see below) + `eop-gha-{plan,apply,deploy}` roles | always | §1b/§1c |
| **GitHub** | repo secrets (`AWS_*_ROLE_ARN`) + `dev` Environment w/ required reviewer | `manage_github` (default **true**) | §2 |

**Elsewhere:** Azure CI app → `../bootstrap-azure` (§3). **Stays manual:** the
workload-app admin consent in §4 (the app only exists after the Phase-3 apply, and
consent needs a human Global Admin), and key rotation §5.

> **OIDC provider is account-wide.** It already exists in this account (shared with
> other stacks), so by default this layer references it as a *data source* and never
> creates or destroys it. For a fresh account with none, set
> `create_oidc_provider=true`.

## Run it once (local, admin credentials)

```bash
cd deploy/terraform/bootstrap

# AWS: admin creds in the shell (the only static creds used, locally, once).
# GitHub: token for the github provider.
export GITHUB_TOKEN=$(gh auth token)

terraform init
terraform apply            # defaults: dsl2022/enterprise-onboarding-project, dev, us-east-1
```

Outputs (role ARNs, state bucket, lock table) are wired into GitHub secrets
automatically when `manage_github=true`. The main stack's `../envs/dev.backend.hcl`
already points at the bucket/table this creates — no edits needed.

### AWS only (skip the GitHub layer)

```bash
terraform apply -var="manage_github=false"   # wire secrets by hand from `terraform output`
```

### Azure CI layer (Phase 3)

Separate module — see [`../bootstrap-azure`](../bootstrap-azure).

## Teardown — destroys ONLY this layer

```bash
terraform destroy
```

Removes the state bucket (`force_destroy=true`), lock table, IAM roles/policies,
and GitHub secrets + environment. It does **not** touch the shared OIDC provider,
the main stack, or the Azure module.

> The state bucket holds the **main stack's** Terraform state. Destroying it
> doesn't delete the main stack's live AWS resources, but it does drop their state.
> If you still have a live main stack, tear it down first via the
> `infra-destroy` workflow. If you only ever applied this bootstrap, just destroy.

## Notes

- **Local state** (`terraform.tfstate` here, git-ignored): holds resource ids, no
  secrets. Keep it to enable a clean `destroy`; otherwise fall back to the manual
  teardown in `RUNBOOK.md` §6.
- **Two providers, two auths:** AWS (shell creds) + GitHub (`GITHUB_TOKEN`). No
  `az` needed here — Azure is the separate `../bootstrap-azure` module.
