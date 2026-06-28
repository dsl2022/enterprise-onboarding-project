locals {
  name_prefix = "eop-${var.env_name}" # e.g. eop-dev
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
