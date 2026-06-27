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
