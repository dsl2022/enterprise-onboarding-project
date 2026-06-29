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

# Phase 4: network (VPC/2AZ/single NAT).
module "network" {
  source      = "./modules/network"
  name_prefix = local.name_prefix
}

# Phase 4: edge (ALB + app CloudFront + SGs). Produces the public HTTPS app URL.
# Separate from compute so module.entra can consume app_url without a dependency cycle.
module "edge" {
  source            = "./modules/edge"
  name_prefix       = local.name_prefix
  vpc_id            = module.network.vpc_id
  public_subnet_ids = module.network.public_subnet_ids

  # An internet-facing ALB requires the VPC's internet gateway to exist first. Subnet
  # references alone don't create that edge, so depend on the whole network module.
  depends_on = [module.network]
}

# Phase 3+4: Entra app registration + federated credential + (Phase 4) Flow 1 redirect
# URI and client secret. issuer_url wired from Phase 2; redirect from the edge app URL.
module "entra" {
  source = "./modules/entra"

  app_display_name = "${local.name_prefix}-app"
  issuer_url       = module.issuer.issuer_url
  workload_subject = local.workload_subject
  redirect_uris = [
    "${module.edge.app_url}/login/oauth2/code/entra", # Flow 1 login callback
    "${module.edge.app_url}/",                        # OIDC post-logout redirect target
  ]
  create_client_secret = true

  # Phase 3a: declare the 6 portal app roles (always) + seed test-user assignments (from tfvars).
  require_app_role_assignment = var.entra_require_app_role_assignment
  app_role_assignments        = var.entra_app_role_assignments
}

# Phase 2: data layer. RDS Postgres (pgvector via Flyway) + ElastiCache Redis (BFF session store),
# both private and reachable only from the app task SG. depends_on the network for the same reason
# edge does — they consume subnets but have no implicit edge to the VPC/subnets being ready.
module "data" {
  source = "./modules/data"

  name_prefix            = local.name_prefix
  vpc_id                 = module.network.vpc_id
  private_subnet_ids     = module.network.private_subnet_ids
  task_security_group_id = module.edge.task_security_group_id
  kms_key_arn            = module.platform.kms_key_arn
  multi_az               = var.multi_az

  depends_on = [module.network]
}

module "cache" {
  source = "./modules/cache"

  name_prefix            = local.name_prefix
  vpc_id                 = module.network.vpc_id
  private_subnet_ids     = module.network.private_subnet_ids
  task_security_group_id = module.edge.task_security_group_id
  kms_key_arn            = module.platform.kms_key_arn
  multi_az               = var.multi_az

  depends_on = [module.network]
}

# Phase 4: ECS Fargate service. Task def + service appear once app_image is set
# (delivered by app-deploy); cluster/roles/secret exist regardless.
module "service" {
  source = "./modules/service"

  name_prefix                   = local.name_prefix
  region                        = var.region
  app_image                     = var.app_image
  private_subnet_ids            = module.network.private_subnet_ids
  task_security_group_id        = module.edge.task_security_group_id
  target_group_arn              = module.edge.target_group_arn
  log_group_name                = module.platform.log_group_name
  kms_key_arn                   = module.platform.kms_key_arn
  issuer_task_access_policy_arn = module.issuer.task_access_policy_arn

  # Phase 2: data + cache wiring (RDS endpoint referenced here makes the service depend on the DB
  # being `available` before tasks roll — TF orders RDS creation first, defusing the boot race).
  db_host              = module.data.endpoint
  db_port              = module.data.port
  db_name              = module.data.db_name
  db_master_secret_arn = module.data.master_user_secret_arn
  redis_host           = module.cache.primary_endpoint
  redis_port           = module.cache.port

  wif_issuer_host         = module.issuer.issuer_url
  wif_issuer_bucket       = module.issuer.issuer_bucket
  wif_signing_secret_name = module.issuer.signing_secret_name
  workload_subject        = local.workload_subject

  entra_tenant_id     = var.entra_tenant_id
  entra_app_client_id = module.entra.app_client_id
  app_url             = module.edge.app_url
  entra_client_secret = module.entra.client_secret_value
  create_flow1_secret = true

  # Phase 4b: real Entra app-registration provisioning. Keep false for the FIRST apply (which only
  # declares Application.ReadWrite.OwnedBy); flip true after a Global Admin grants admin consent.
  provisioning_real = var.provisioning_real
}
