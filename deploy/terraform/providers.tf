provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "enterprise-onboarding"
      Env       = var.env_name
      ManagedBy = "terraform"
    }
  }
}

# Azure AD / Entra. In CI, authenticates via GitHub OIDC: the workflow sets
# ARM_USE_OIDC=true, ARM_CLIENT_ID (eop-github-ci app), ARM_TENANT_ID. No secret.
provider "azuread" {
  use_oidc = true
}
