env_name           = "dev"
region             = "us-east-1"
log_retention_days = 14
entra_tenant_id    = "5b7b1ae8-bcc5-485f-ad46-cb099090670e"
# app_image is injected by app-deploy.yml via -var; left unset for plain infra plans.

# ---- Phase 3a: portal RBAC seed (Entra app roles) ----
# principal_object_id = the user's Entra object id (objectIds aren't secret). Assign EVERY interactive
# login a role BEFORE flipping entra_require_app_role_assignment=true, or sign-in breaks. RUNBOOK §8.
#   GA  Jizong Liang (job2019tmm_gmail.com#EXT#@…) = eabe2f29-7b13-4991-bccc-804869d55c30 → SUPER_ADMIN
#       (test every role via impersonation)
#   testuser@job2019tmmgmail.onmicrosoft.com       = 31770d7d-c707-4115-94b7-8f2dbd1cfaf6 → APPLICATION_OWNER
#       (a real, non-super owner: exercises ABAC ownership + SoD-as-requester)
entra_app_role_assignments = [
  { role = "SUPER_ADMIN", principal_object_id = "eabe2f29-7b13-4991-bccc-804869d55c30" },
  { role = "APPLICATION_OWNER", principal_object_id = "31770d7d-c707-4115-94b7-8f2dbd1cfaf6" },
]

# Leave enforcement OFF for this first assignment apply. After verifying both logins resolve their roles
# at /api/v1/me (and Super Admin impersonation works), set this true in a follow-up apply to require an
# app-role assignment for sign-in.
# entra_require_app_role_assignment = true

# ---- Phase 4b/5b: per-vertical real provisioning ----
# Both verticals real: Application.ReadWrite.OwnedBy (4b) + GroupMember.ReadWrite.All (5b) are both
# admin-consented on the eop-dev-app SP (2026-06-29), consent in place BEFORE the flip. access=true sets
# EOP_PROVISIONING_ACCESS_SIMULATE=false → the real GraphGroupMembershipProvisioner; the post-flip task
# roll mints a fresh token that carries GroupMember (access was simulated until now, so no cached pre-consent
# token issue).
onboarding_provisioning_real = true
access_provisioning_real     = true
