# GitHub Actions -> AWS via OIDC. No long-lived keys: GitHub mints a short-lived
# token per run, AWS trusts GitHub's issuer (the OIDC provider), and each workflow
# assumes a role scoped to a specific repo + trigger via the token's `sub` claim.
#
# Three roles mirror CI's separation of duties (see ../../.github/workflows):
#   plan   (read-only) -> infra.yml plan job   (pull_request)
#   apply  (write)     -> infra.yml apply job   (env-gated)
#   deploy (app)       -> app-deploy.yml        (push to main)
#
# Replaces RUNBOOK sections 1b + 1c.

data "aws_caller_identity" "current" {}

# --- OIDC identity provider (account-wide singleton) ------------------------
# Default: reference the existing provider as a data source so this layer never
# owns or destroys it (other stacks in the account share it). Set
# create_oidc_provider = true only in a fresh account that has none.

data "tls_certificate" "github" {
  count = var.create_oidc_provider ? 1 : 0
  url   = "https://token.actions.githubusercontent.com/.well-known/openid-configuration"
}

resource "aws_iam_openid_connect_provider" "github" {
  count           = var.create_oidc_provider ? 1 : 0
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.github[0].certificates[0].sha1_fingerprint]
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

locals {
  oidc_provider_arn = var.create_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn

  repo = "${var.github_owner}/${var.github_repo}"

  # The `sub` claim GitHub sets depends on the trigger:
  #   pull_request job        -> repo:OWNER/REPO:pull_request
  #   job with `environment:` -> repo:OWNER/REPO:environment:<name>
  #   push to a branch        -> repo:OWNER/REPO:ref:refs/heads/<branch>
  sub_pull_request = "repo:${local.repo}:pull_request"
  sub_environment  = "repo:${local.repo}:environment:${var.env_name}"
  sub_main_branch  = "repo:${local.repo}:ref:refs/heads/${var.default_branch}"
}

# Builds an sts:AssumeRoleWithWebIdentity trust doc locked to our provider,
# audience, and exactly one allowed `sub` (the security boundary).
data "aws_iam_policy_document" "trust" {
  for_each = {
    plan   = local.sub_pull_request
    apply  = local.sub_environment
    deploy = local.sub_main_branch
  }

  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = [each.value]
    }
  }
}

# --- backend access for the read-only plan role -----------------------------
# `terraform plan` reads remote state and takes the lock, which ReadOnlyAccess
# alone doesn't grant. Scope just this env's state bucket + the lock table.
data "aws_iam_policy_document" "tf_backend" {
  statement {
    sid       = "StateBucket"
    effect    = "Allow"
    actions   = ["s3:ListBucket", "s3:GetObject", "s3:PutObject"]
    resources = [aws_s3_bucket.state.arn, "${aws_s3_bucket.state.arn}/*"]
  }
  statement {
    sid       = "StateLock"
    effect    = "Allow"
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:DeleteItem"]
    resources = [aws_dynamodb_table.lock.arn]
  }
  # `terraform plan` refreshes secret-version resources (e.g. the Flow 1 client secret),
  # which calls GetSecretValue + kms:Decrypt — both excluded from the managed ReadOnlyAccess.
  statement {
    sid       = "ReadProjectSecrets"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = ["arn:aws:secretsmanager:*:${data.aws_caller_identity.current.account_id}:secret:eop-*"]
  }
  statement {
    sid     = "DecryptForSecretRefresh"
    effect  = "Allow"
    actions = ["kms:Decrypt"]
    # Only when used via Secrets Manager, so this read-only role can't decrypt arbitrarily.
    resources = ["*"]
    condition {
      test     = "StringLike"
      variable = "kms:ViaService"
      values   = ["secretsmanager.*.amazonaws.com"]
    }
  }
}

resource "aws_iam_policy" "tf_backend" {
  name   = "eop-tf-backend"
  policy = data.aws_iam_policy_document.tf_backend.json
}

# --- the three roles --------------------------------------------------------

# PLAN: read-only + state-backend access. Produces the PR diff.
resource "aws_iam_role" "plan" {
  name               = "eop-gha-plan"
  assume_role_policy = data.aws_iam_policy_document.trust["plan"].json
}

resource "aws_iam_role_policy_attachment" "plan_readonly" {
  role       = aws_iam_role.plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

resource "aws_iam_role_policy_attachment" "plan_backend" {
  role       = aws_iam_role.plan.name
  policy_arn = aws_iam_policy.tf_backend.arn
}

# APPLY: provisions the stack, gated by the dev GitHub Environment. Admin for v0;
# scope down post-v0 (TODO in RUNBOOK 1c).
resource "aws_iam_role" "apply" {
  name               = "eop-gha-apply"
  assume_role_policy = data.aws_iam_policy_document.trust["apply"].json
}

resource "aws_iam_role_policy_attachment" "apply_admin" {
  role       = aws_iam_role.apply.name
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
}

# DEPLOY: app build/push on main. Admin for v0; scope to ECR+SSM+(Phase4 ECS) post-v0.
resource "aws_iam_role" "deploy" {
  name               = "eop-gha-deploy"
  assume_role_policy = data.aws_iam_policy_document.trust["deploy"].json
}

resource "aws_iam_role_policy_attachment" "deploy_admin" {
  role       = aws_iam_role.deploy.name
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
}
