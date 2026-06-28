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
