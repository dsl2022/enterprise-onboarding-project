env_name           = "dev"
region             = "us-east-1"
log_retention_days = 14
entra_tenant_id    = "5b7b1ae8-bcc5-485f-ad46-cb099090670e"
# app_image is injected by app-deploy.yml via -var; left unset for plain infra plans.

# ---- Phase 3a: portal RBAC seed (Entra app roles) ----
# Assign test users to the 6 app roles. principal_object_id = the user's Entra object id
# (az ad user show --id <upn> --query id -o tsv). objectIds aren't secret. Include ONE multi-role
# user (two rows, same id) to exercise the permission union. Assign EVERY login (testuser + your GA)
# a role BEFORE flipping entra_require_app_role_assignment=true, or sign-in breaks. See RUNBOOK §8.
# entra_app_role_assignments = [
#   { role = "APPLICATION_OWNER", principal_object_id = "<owner-user-oid>" },
#   { role = "SSO_OPERATIONS",    principal_object_id = "<ops-user-oid>" },
#   { role = "ADMIN",             principal_object_id = "<admin-user-oid>" },
#   { role = "AUDITOR",           principal_object_id = "<auditor-user-oid>" },
#   { role = "READ_ONLY",         principal_object_id = "<readonly-user-oid>" },
#   { role = "SUPER_ADMIN",       principal_object_id = "<superadmin-user-oid>" },
#   { role = "AUDITOR",           principal_object_id = "<owner-user-oid>" }, # multi-role: owner+auditor union
# ]
# entra_require_app_role_assignment = true   # flip ONLY after the assignments above are applied & verified
