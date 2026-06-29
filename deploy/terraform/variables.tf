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

variable "entra_require_app_role_assignment" {
  description = "Phase 3a: require an app-role assignment to sign in (interactive Flow-1 only). Default false; flip true AFTER assigning roles to every login or it breaks them."
  type        = bool
  default     = false
}

variable "entra_app_role_assignments" {
  description = "Phase 3a: portal app-role assignments for test users. Provided at apply via envs/dev.tfvars (objectIds aren't secret)."
  type = list(object({
    role                = string
    principal_object_id = string
  }))
  default = []
}

variable "provisioning_real" {
  description = "Phase 4b: enable real Entra app-registration provisioning. Keep false for the first apply (declares Application.ReadWrite.OwnedBy only); flip true after a Global Admin grants admin consent."
  type        = bool
  default     = false
}
