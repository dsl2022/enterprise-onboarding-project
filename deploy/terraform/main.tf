locals {
  name_prefix = "eop-${var.env_name}" # e.g. eop-dev
  # Subject the AWS workload mints into its assertion; the Entra federated credential matches on it.
  workload_subject = "${local.name_prefix}-workload"
}

# Phase 1: platform primitives only (KMS CMK, ECR, log group). The pipeline has something real to
# plan/apply, and app-deploy.yml has an ECR repo to push to.
# Phase 2 adds the workload OIDC issuer (S3 + CloudFront) module.
# Phase 4 adds network (VPC/ALB/NAT) + the ECS Fargate service module.
module "platform" {
  source = "./modules/platform"

  name_prefix        = local.name_prefix
  log_retention_days = var.log_retention_days
}

# Phase 2: workload OIDC issuer (private S3 + CloudFront/OAC + discovery doc +
# empty signing secret + task IAM policy). The app publishes jwks.json at runtime.
module "issuer" {
  source = "./modules/issuer"

  name_prefix = local.name_prefix
  kms_key_arn = module.platform.kms_key_arn
}

# Phase 3: Entra app registration + federated identity credential pointing at the
# Phase-2 issuer URL (same state — no manual copy). Graph Group.Read.All is declared
# here; a Global Admin grants admin consent out-of-band (RUNBOOK §4).
module "entra" {
  source = "./modules/entra"

  app_display_name = "${local.name_prefix}-app"
  issuer_url       = module.issuer.issuer_url
  workload_subject = local.workload_subject
  # redirect_uris set in Phase 4 once the ALB DNS exists.
}
