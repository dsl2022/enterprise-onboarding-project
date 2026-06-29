variable "name_prefix" { type = string }
variable "region" { type = string }

variable "app_image" {
  description = "Container image URI. Empty => task def + service are not created yet (image delivered by app-deploy)."
  type        = string
  default     = ""
}

variable "private_subnet_ids" { type = list(string) }
variable "task_security_group_id" { type = string }
variable "target_group_arn" { type = string }
variable "container_port" {
  type    = number
  default = 8080
}

variable "log_group_name" { type = string }
variable "kms_key_arn" { type = string }
variable "issuer_task_access_policy_arn" { type = string }

variable "cpu" {
  type    = number
  default = 256
}
variable "memory" {
  type    = number
  default = 512
}

# --- app env / config ---
variable "wif_issuer_host" { type = string }
variable "wif_issuer_bucket" { type = string }
variable "wif_signing_secret_name" { type = string }
variable "workload_subject" { type = string }

variable "entra_tenant_id" { type = string }
variable "entra_app_client_id" { type = string }
variable "app_url" { type = string }

variable "entra_client_secret" {
  description = "Flow 1 confidential-client secret value; stored in Secrets Manager and injected into the task."
  type        = string
  sensitive   = true
  default     = ""
}

variable "create_flow1_secret" {
  description = "Write the Flow 1 client secret version. A static bool (the secret VALUE is unknown until apply, so it can't gate count)."
  type        = bool
  default     = false
}

# --- Phase 2: data + cache (DB password injected from the RDS-managed secret; never an env literal) ---
variable "db_host" { type = string }
variable "db_port" {
  type    = number
  default = 5432
}
variable "db_name" { type = string }
variable "db_master_secret_arn" {
  description = "ARN of the RDS-managed master-user secret (CMK-encrypted JSON). username/password injected via ECS secrets."
  type        = string
}
variable "redis_host" { type = string }
variable "redis_port" {
  type    = number
  default = 6379
}

# Per-vertical real-provisioning toggles. Each flips ONLY its vertical's `simulate` to false, and ONLY
# after that vertical's Graph permission is admin-consented (RUNBOOK §9). They are independent so onboarding
# (4b) can go real while access (5b) stays simulated — the shared-flag coupling that crash-looped the task
# is gone. Schedulers run regardless (set in the service env), so a simulated vertical still completes.
variable "onboarding_provisioning_real" {
  description = "Real Entra app-registration provisioning (4b). Requires Application.ReadWrite.OwnedBy consent."
  type        = bool
  default     = false
}

variable "access_provisioning_real" {
  description = "Real Entra group-membership provisioning (5b). Requires GroupMember.ReadWrite.All consent + the real provisioner."
  type        = bool
  default     = false
}
