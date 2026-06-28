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
  description = "Flow 1 (login) web redirect URIs. Empty in Phase 3; set to the app callback in Phase 4."
  type        = list(string)
  default     = []
}

variable "create_client_secret" {
  description = "Create the Flow 1 confidential-client secret (Phase 4). Off in Phase 3."
  type        = bool
  default     = false
}

variable "require_app_role_assignment" {
  description = "If true, only users assigned an app role can sign in (interactive Flow-1 only; WIF unaffected). Default false: flip on AFTER assigning roles to every login (incl. testuser + GA), else login breaks."
  type        = bool
  default     = false
}

variable "app_role_assignments" {
  description = "Test-user portal app-role seed. Each: {role = one of the 6 role values, principal_object_id = Entra user objectId}. Human-provided at apply (envs/dev.tfvars); empty keeps plans valid before objectIds exist."
  type = list(object({
    role                = string
    principal_object_id = string
  }))
  default = []

  validation {
    condition = alltrue([
      for a in var.app_role_assignments :
      contains(["APPLICATION_OWNER", "SSO_OPERATIONS", "ADMIN", "AUDITOR", "READ_ONLY", "SUPER_ADMIN"], a.role)
    ])
    error_message = "Each app_role_assignments.role must be one of the six portal roles."
  }
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
