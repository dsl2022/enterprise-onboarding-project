# Self-contained bootstrap layer. Deliberately NO backend block: this config keeps
# LOCAL state (terraform.tfstate in this dir) and is completely separate from the
# main stack (../). It has its own state, so `terraform destroy` here removes ONLY
# what this layer created — it can never touch the main stack's resources or state.
#
# Run locally, once, by a human with admin credentials. See README.md.

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "enterprise-onboarding"
      Component = "bootstrap"
      ManagedBy = "terraform"
    }
  }
}

# GitHub provider authenticates from GITHUB_TOKEN (or GH_TOKEN) in the environment.
# Export one before apply:  export GITHUB_TOKEN=$(gh auth token)
provider "github" {
  owner = var.github_owner
}
