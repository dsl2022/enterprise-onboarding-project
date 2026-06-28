variable "name_prefix" {
  description = "Resource name prefix, e.g. eop-dev."
  type        = string
}

variable "kms_key_arn" {
  description = "Project CMK ARN used to encrypt the issuer signing secret."
  type        = string
}
