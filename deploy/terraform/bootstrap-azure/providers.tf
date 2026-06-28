# Self-contained Phase-3 Azure CI layer. Own LOCAL state, separate from the core
# bootstrap (../bootstrap) and the main stack (../). `terraform destroy` here
# removes ONLY the Entra CI app + its GitHub secrets — nothing else.

# Authenticates from your `az login` session (tenant admin).
provider "azuread" {
  tenant_id = var.tenant_id
}

# Sets the two AZURE_* GitHub secrets. Auth from GITHUB_TOKEN (export GITHUB_TOKEN=$(gh auth token)).
provider "github" {
  owner = var.github_owner
}
