# GitHub Actions -> Azure (Entra) via OIDC, for the CI identity that manages the
# workload app registration starting Phase 3. No client secret anywhere: GitHub's
# token is exchanged for an Azure token via federated credentials.
#
# Replaces RUNBOOK section 3.
# Pinned to azuread ~> 2.53 syntax (application_object_id on the FIC,
# application_id on the SP / Graph data source).

locals {
  repo = "${var.github_owner}/${var.github_repo}"

  # Microsoft Graph app id + the Application.ReadWrite.OwnedBy app-role id.
  # (Verified against the live Graph service principal; this is a global constant.)
  graph_app_id      = "00000003-0000-0000-c000-000000000000"
  graph_app_role_id = "18a4783c-866b-4cc7-a460-3d5e5662c884"

  # One federated credential per GitHub subject (same multi-subject pattern as AWS).
  fics = {
    "gh-pr"   = "repo:${local.repo}:pull_request"
    "gh-dev"  = "repo:${local.repo}:environment:${var.env_name}"
    "gh-main" = "repo:${local.repo}:ref:refs/heads/${var.default_branch}"
  }
}

data "azuread_service_principal" "msgraph" {
  application_id = local.graph_app_id
}

# CI app registration + service principal (no password/secret created).
resource "azuread_application" "ci" {
  display_name = "eop-github-ci"

  # Graph application permission to manage app registrations it owns. Granted
  # (admin-consented) below via azuread_app_role_assignment.
  required_resource_access {
    resource_app_id = local.graph_app_id
    resource_access {
      id   = local.graph_app_role_id
      type = "Role"
    }
  }
}

resource "azuread_service_principal" "ci" {
  application_id = azuread_application.ci.application_id
}

resource "azuread_application_federated_identity_credential" "gh" {
  for_each = local.fics

  application_object_id = azuread_application.ci.object_id
  display_name          = each.key
  description           = "GitHub OIDC: ${each.value}"
  issuer                = "https://token.actions.githubusercontent.com"
  subject               = each.value
  audiences             = ["api://AzureADTokenExchange"]
}

# Admin consent: grant the Application.ReadWrite.OwnedBy app role to the CI SP.
# Terraform equivalent of `az ad app permission admin-consent`; requires the
# running identity to be a tenant admin (Privileged Role Admin / Global Admin).
resource "azuread_app_role_assignment" "graph_owned_by" {
  app_role_id         = local.graph_app_role_id
  principal_object_id = azuread_service_principal.ci.object_id
  resource_object_id  = data.azuread_service_principal.msgraph.object_id
}

# AZURE_* GitHub secrets (consumed by azure/login starting Phase 3).
resource "github_actions_secret" "azure_client_id" {
  count           = var.manage_github_secrets ? 1 : 0
  repository      = var.github_repo
  secret_name     = "AZURE_CLIENT_ID"
  plaintext_value = azuread_application.ci.application_id
}

resource "github_actions_secret" "azure_tenant_id" {
  count           = var.manage_github_secrets ? 1 : 0
  repository      = var.github_repo
  secret_name     = "AZURE_TENANT_ID"
  plaintext_value = var.tenant_id
}
