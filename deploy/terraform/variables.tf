variable "env_name" {
  description = "Environment name (matches the tfvars/backend file and the GitHub Environment)."
  type        = string
}

variable "region" {
  description = "AWS region."
  type        = string
  default     = "us-east-1"
}

variable "app_image" {
  description = "Container image URI for the app (injected by app-deploy.yml via -var; empty in plain infra plans)."
  type        = string
  default     = ""
}

variable "entra_tenant_id" {
  description = "Microsoft Entra tenant id (Flow 1 OIDC authority)."
  type        = string
}

variable "log_retention_days" {
  description = "CloudWatch log retention."
  type        = number
  default     = 14
}
