variable "github_owner" {
  description = "GitHub org/user that owns the repo (and the OIDC sub prefix)."
  type        = string
  default     = "dsl2022"
}

variable "github_repo" {
  description = "GitHub repository name."
  type        = string
  default     = "enterprise-onboarding-project"
}

variable "env_name" {
  description = "Environment name (the federated credential trusts sub = ...:environment:<env>)."
  type        = string
  default     = "dev"
}

variable "default_branch" {
  description = "Branch the main federated credential trusts (sub = ...:ref:refs/heads/<branch>)."
  type        = string
  default     = "main"
}

variable "tenant_id" {
  description = "Azure AD (Entra) tenant id."
  type        = string
  default     = "5b7b1ae8-bcc5-485f-ad46-cb099090670e"
}

variable "manage_github_secrets" {
  description = "Push AZURE_CLIENT_ID / AZURE_TENANT_ID into GitHub repo secrets (needs GITHUB_TOKEN). Set false to wire them by hand from outputs."
  type        = bool
  default     = true
}
