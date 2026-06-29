variable "name_prefix" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "container_port" {
  type    = number
  default = 8080
}

# ---- SPA (/app) origin, wired from module.webapp ---------------------------
# The Angular app is an additional S3 origin + behavior on THIS distribution so
# it's same-origin with the BFF. The bucket/OAC/function live in module.webapp.

variable "spa_bucket_regional_domain_name" {
  type        = string
  description = "module.webapp.bucket_regional_domain_name — S3 origin for /app."
}

variable "spa_bucket_id" {
  type        = string
  description = "module.webapp.bucket_name — for the OAC bucket policy."
}

variable "spa_bucket_arn" {
  type        = string
  description = "module.webapp.bucket_arn — for the OAC bucket policy."
}

variable "spa_oac_id" {
  type        = string
  description = "module.webapp.oac_id — Origin Access Control for the SPA origin."
}

variable "spa_function_arn" {
  type        = string
  description = "module.webapp.function_arn — SPA deep-link fallback (viewer-request)."
}
