variable "app_display_name" {
  description = "Entra app registration display name."
  type        = string
}

variable "issuer_url" {
  description = "Workload OIDC issuer host (https://<cloudfront-domain>) from the issuer module. Must equal the discovery doc's `issuer`."
  type        = string
}

variable "workload_subject" {
  description = "Subject claim the AWS workload puts in its assertion; the federated credential matches on it exactly."
  type        = string
}

variable "redirect_uris" {
  description = "Flow 1 (login) web redirect URIs. Empty in Phase 3; set to the ALB callback in Phase 4."
  type        = list(string)
  default     = []
}

variable "graph_app_id" {
  description = "Microsoft Graph resource app id (global constant)."
  type        = string
  default     = "00000003-0000-0000-c000-000000000000"
}

variable "group_read_all_role_id" {
  description = "Graph application app-role id for Group.Read.All (verified against the live Graph SP)."
  type        = string
  default     = "5b567255-7703-4780-807c-7be8301ae99b"
}
