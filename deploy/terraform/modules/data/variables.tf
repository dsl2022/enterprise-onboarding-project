variable "name_prefix" { type = string }
variable "vpc_id" { type = string }
variable "private_subnet_ids" { type = list(string) }

variable "task_security_group_id" {
  description = "The Fargate task SG; the only source allowed to reach Postgres on 5432."
  type        = string
}

variable "kms_key_arn" {
  description = "Project CMK. Encrypts storage AND the RDS-managed master-user secret, so the existing exec-role kms:Decrypt grant on this key covers secret injection (no aws/secretsmanager default key)."
  type        = string
}

variable "multi_az" {
  description = "Static bool: prod HA. Dev default is single-AZ (cheaper; data is disposable per ADR)."
  type        = bool
  default     = false
}

variable "db_name" {
  type    = string
  default = "eop"
}

variable "engine_version" {
  type    = string
  default = "16"
}

variable "instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "allocated_storage" {
  type    = number
  default = 20
}
