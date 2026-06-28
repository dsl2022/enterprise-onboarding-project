# Single Entra app registration carrying both flows (ADR-0006):
#  - Flow 1 (login): web redirect URIs (added in Phase 4 once the ALB exists).
#  - Flow 2 (WIF):   federated identity credential trusting our self-hosted issuer.
# Plus the application Graph permission Group.Read.All (admin-consented out-of-band by a
# Global Admin — the CI identity only holds Application.ReadWrite.OwnedBy, so Terraform
# declares the permission but does NOT grant consent). Pinned to azuread ~> 2.53 syntax.

resource "azuread_application" "app" {
  display_name = var.app_display_name

  required_resource_access {
    resource_app_id = var.graph_app_id
    resource_access {
      id   = var.group_read_all_role_id # Group.Read.All
      type = "Role"                     # application permission
    }
  }

  dynamic "web" {
    for_each = length(var.redirect_uris) > 0 ? [1] : []
    content {
      redirect_uris = var.redirect_uris
    }
  }
}

resource "azuread_service_principal" "app" {
  application_id = azuread_application.app.application_id
}

# The centerpiece: trust assertions signed by our AWS-hosted issuer. issuer + subject +
# audience must all match what the workload mints (Phase 5). No client secret involved.
resource "azuread_application_federated_identity_credential" "wif" {
  application_object_id = azuread_application.app.object_id
  display_name          = "aws-fargate-wif"
  description           = "AWS Fargate workload identity via self-hosted OIDC issuer"
  issuer                = var.issuer_url
  subject               = var.workload_subject
  audiences             = ["api://AzureADTokenExchange"]
}

# Flow 1 (login) confidential-client secret. The one accepted stored secret for v0
# (ADR-0006); the caller writes it to AWS Secrets Manager. Upgrade to a certificate later.
resource "azuread_application_password" "flow1" {
  count                 = var.create_client_secret ? 1 : 0
  application_object_id = azuread_application.app.object_id
  display_name          = "flow1-bff-secret"
  end_date_relative     = "4320h" # 180 days
}
