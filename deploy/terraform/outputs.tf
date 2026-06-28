output "ecr_repository_url" {
  description = "ECR repo the app image is pushed to."
  value       = module.platform.ecr_repository_url
}

output "kms_key_arn" {
  description = "Account CMK for the project (logs, and later Secrets Manager)."
  value       = module.platform.kms_key_arn
}

output "log_group_name" {
  description = "App CloudWatch log group."
  value       = module.platform.log_group_name
}

# ---- Phase 2: workload OIDC issuer ----
output "issuer_url" {
  description = "Issuer host (https://<cloudfront-domain>). Feeds iss + the Entra federated credential (Phase 3)."
  value       = module.issuer.issuer_url
}

output "issuer_bucket" {
  value = module.issuer.issuer_bucket
}

output "issuer_signing_secret_arn" {
  value = module.issuer.signing_secret_arn
}

output "issuer_task_access_policy_arn" {
  value = module.issuer.task_access_policy_arn
}

# ---- Phase 3: Entra config ----
output "entra_app_client_id" {
  description = "Run admin consent on this client id (RUNBOOK §4); also the client_id for the WIF exchange."
  value       = module.entra.app_client_id
}

output "entra_service_principal_object_id" {
  value = module.entra.service_principal_object_id
}

output "workload_subject" {
  description = "WIF subject — set as the app's WIF_SUBJECT env in Phase 4."
  value       = module.entra.workload_subject
}
