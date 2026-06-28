terraform {
  required_version = ">= 1.6.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
    github = {
      source  = "integrations/github"
      version = "~> 6.2"
    }
    # Azure (Entra) CI app lives in the separate sibling module ../bootstrap-azure
    # so this core layer never needs `az login`. See that folder for Phase 3.
  }
}
