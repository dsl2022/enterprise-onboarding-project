variable "name_prefix" {
  description = "Resource name prefix, e.g. eop-dev."
  type        = string
}

variable "log_retention_days" {
  description = "CloudWatch log retention in days."
  type        = number
  default     = 14
}
