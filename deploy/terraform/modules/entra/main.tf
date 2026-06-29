# Single Entra app registration carrying both flows (ADR-0006):
#  - Flow 1 (login): web redirect URIs (added in Phase 4 once the ALB exists).
#  - Flow 2 (WIF):   federated identity credential trusting our self-hosted issuer.
# Plus the application Graph permission Group.Read.All (admin-consented out-of-band by a
# Global Admin — the CI identity only holds Application.ReadWrite.OwnedBy, so Terraform
# declares the permission but does NOT grant consent). Pinned to azuread ~> 2.53 syntax.
#
# Phase 3a: the SIX portal app roles (RBAC source — the token `roles` claim). The `id`s are FIXED
# UUIDs (not resolved live like Graph GUIDs — these are ours to declare); changing one orphans its
# assignments. Assigned to users (not groups). Roles drive portal RBAC; Entra GROUP membership is a
# separate access-governance concern (the directory module reads groups; RBAC never does).

locals {
  portal_app_roles = {
    APPLICATION_OWNER = { id = "11111111-1111-4111-8111-111111111111", display = "Application Owner", desc = "Create/manage own apps + team; submit requests." }
    SSO_OPERATIONS    = { id = "22222222-2222-4222-8222-222222222222", display = "SSO Operations", desc = "Review/approve/provision; rotate secrets." }
    ADMIN             = { id = "33333333-3333-4333-8333-333333333333", display = "Admin", desc = "Full access across all resources." }
    AUDITOR           = { id = "44444444-4444-4444-8444-444444444444", display = "Auditor", desc = "Read-only, audit-focused." }
    READ_ONLY         = { id = "55555555-5555-4555-8555-555555555555", display = "Read Only", desc = "Read-only views, no actions." }
    SUPER_ADMIN       = { id = "66666666-6666-4666-8666-666666666666", display = "Super Admin", desc = "God mode: full access + audited impersonation." }
  }
}

resource "azuread_application" "app" {
  display_name = var.app_display_name

  required_resource_access {
    resource_app_id = var.graph_app_id
    resource_access {
      id   = var.group_read_all_role_id # Group.Read.All
      type = "Role"                     # application permission
    }
    # Phase 4b: create app registrations for onboarding (find-or-create over the WIF token). OwnedBy, not
    # ReadWrite.All — least privilege; yields a registration + client id, not a service principal.
    resource_access {
      id   = var.app_readwrite_ownedby_role_id # Application.ReadWrite.OwnedBy
      type = "Role"                            # application permission
    }
    # Phase 5b: add/remove access-grant group members. NOTE: Graph has no per-group app-only scope for
    # membership writes — this grant is tenant-broad (any group). Acceptable for dev; constrain/monitor for
    # prod (tracked CR). See ADR-0019.
    resource_access {
      id   = var.group_member_readwrite_all_role_id # GroupMember.ReadWrite.All
      type = "Role"                                 # application permission
    }
  }

  dynamic "app_role" {
    for_each = local.portal_app_roles
    content {
      id                   = app_role.value.id
      allowed_member_types = ["User"]
      value                = app_role.key # the value that appears in the token `roles` claim
      display_name         = app_role.value.display
      description          = app_role.value.desc
      enabled              = true
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

  # When true, only users assigned an app role can sign in — kills the "authenticated, zero roles"
  # edge. Default false so flipping it on is a deliberate, post-assignment step (else every interactive
  # login, incl. testuser + your GA, must already hold a role or it breaks). Flow-2 WIF is app-only and
  # unaffected either way.
  app_role_assignment_required = var.require_app_role_assignment
}

# Phase 3a: assign test users to portal app roles (RBAC seed). principal_object_id = the user's Entra
# object id (human-provided at apply via envs/dev.tfvars). Empty by default so plans stay valid before
# the objectIds are supplied. Include one multi-role user to exercise the permission union.
resource "azuread_app_role_assignment" "users" {
  for_each = { for a in var.app_role_assignments : "${a.role}:${a.principal_object_id}" => a }

  app_role_id         = local.portal_app_roles[each.value.role].id
  principal_object_id = each.value.principal_object_id
  resource_object_id  = azuread_service_principal.app.object_id
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
