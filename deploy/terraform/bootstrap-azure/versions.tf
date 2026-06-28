terraform {
  required_version = ">= 1.6.0, < 2.0.0"

  required_providers {
    azuread = {
      source  = "hashicorp/azuread"
      version = "~> 2.53"
    }
    github = {
      source  = "integrations/github"
      version = "~> 6.2"
    }
  }
}
