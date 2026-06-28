terraform {
  required_version = ">= 1.6.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
    # Phase 3: Entra app registration + federated credential. No azurerm /
    # subscription — Azure is config-only. See DECISIONS ADR-0003.
    azuread = {
      source  = "hashicorp/azuread"
      version = "~> 2.53"
    }
  }
}
