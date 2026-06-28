# GitHub repo secrets + the dev Environment with a required reviewer.
# Replaces RUNBOOK section 2. Needs GITHUB_TOKEN (export GITHUB_TOKEN=$(gh auth token)).
# Gate with manage_github=false to skip this layer entirely.

data "github_user" "me" {
  count    = var.manage_github ? 1 : 0
  username = var.github_owner
}

# Role ARNs are public identifiers, not secrets — but the workflows read them from
# secrets, so we set them here for a one-shot, reproducible setup.
resource "github_actions_secret" "aws_plan_role_arn" {
  count           = var.manage_github ? 1 : 0
  repository      = var.github_repo
  secret_name     = "AWS_PLAN_ROLE_ARN"
  plaintext_value = aws_iam_role.plan.arn
}

resource "github_actions_secret" "aws_apply_role_arn" {
  count           = var.manage_github ? 1 : 0
  repository      = var.github_repo
  secret_name     = "AWS_APPLY_ROLE_ARN"
  plaintext_value = aws_iam_role.apply.arn
}

resource "github_actions_secret" "aws_deploy_role_arn" {
  count           = var.manage_github ? 1 : 0
  repository      = var.github_repo
  secret_name     = "AWS_DEPLOY_ROLE_ARN"
  plaintext_value = aws_iam_role.deploy.arn
}

# AZURE_CLIENT_ID / AZURE_TENANT_ID secrets are managed by the sibling
# ../bootstrap-azure module (Phase 3), alongside the Entra app they describe.

# The dev Environment's required reviewer IS the apply guardrail (replaces
# "ask before apply"). The apply role's trust is keyed on this environment's sub.
resource "github_repository_environment" "dev" {
  count       = var.manage_github ? 1 : 0
  repository  = var.github_repo
  environment = var.env_name

  reviewers {
    users = [data.github_user.me[0].id]
  }
}
