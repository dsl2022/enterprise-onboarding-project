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

# Phase 4b: flip to true ONLY AFTER admin consent for Application.ReadWrite.OwnedBy is granted (RUNBOOK).
# false keeps the SimulatedProvisioner active; true sets EOP_PROVISIONING_SIMULATE=false + enables the
# scheduler so the worker provisions real Entra app registrations over the WIF token.
variable "provisioning_real" {
  description = "Enable real Graph app-registration provisioning (requires admin consent already granted)."
  type        = bool
  default     = false
}
