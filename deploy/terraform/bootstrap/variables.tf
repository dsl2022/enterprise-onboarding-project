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

variable "region" {
  description = "AWS region for the state backend + roles."
  type        = string
  default     = "us-east-1"
}

variable "env_name" {
  description = "Environment name. Feeds the state bucket name and the apply role's trusted environment subject. Must match ../envs/<env>.backend.hcl."
  type        = string
  default     = "dev"
}

variable "default_branch" {
  description = "Branch the deploy role trusts (sub = ...:ref:refs/heads/<branch>)."
  type        = string
  default     = "main"
}

variable "lock_table_name" {
  description = "DynamoDB table name for Terraform state locking. Must match ../envs/<env>.backend.hcl."
  type        = string
  default     = "eop-tflock"
}

# --- feature toggles: enable a layer only when you have its credentials ready ---

variable "create_oidc_provider" {
  description = "Create the account-wide GitHub OIDC provider. Leave false when it already exists in the account (it is a singleton shared by other stacks); the config then references it as a data source and never destroys it. Set true only for a fresh account that has none."
  type        = bool
  default     = false
}

variable "manage_github" {
  description = "Manage GitHub repo secrets + the dev Environment (needs GITHUB_TOKEN). RUNBOOK section 2."
  type        = bool
  default     = true
}
