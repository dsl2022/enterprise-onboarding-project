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

variable "multi_az" {
  description = "Static bool: prod HA for the data + cache layers (RDS Multi-AZ, Redis replica + failover). Dev default is single-AZ/single-node (cheaper; Redis is then a session SPOF, not HA)."
  type        = bool
  default     = false
}
