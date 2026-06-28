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
