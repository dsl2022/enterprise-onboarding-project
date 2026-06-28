variable "name_prefix" { type = string }
variable "vpc_id" { type = string }
variable "private_subnet_ids" { type = list(string) }

variable "task_security_group_id" {
  description = "The Fargate task SG; the only source allowed to reach Redis on 6379."
  type        = string
}

variable "multi_az" {
  description = <<-EOT
    Static bool. false (dev default) = a SINGLE node — this is a session SPOF, NOT highly available;
    if it dies, active BFF sessions are lost and users re-login. true = a primary + replica with
    automatic failover (prod HA). Honest labeling per the AS-BUILT landmine.
  EOT
  type        = bool
  default     = false
}

variable "node_type" {
  type    = string
  default = "cache.t4g.micro"
}

variable "engine_version" {
  type    = string
  default = "7.1"
}
