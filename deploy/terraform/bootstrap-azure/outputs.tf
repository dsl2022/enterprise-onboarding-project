output "azure_client_id" {
  description = "eop-github-ci app (client) id. Wired into the AZURE_CLIENT_ID secret when manage_github_secrets=true."
  value       = azuread_application.ci.application_id
}

output "azure_tenant_id" {
  value = var.tenant_id
}

output "service_principal_object_id" {
  value = azuread_service_principal.ci.object_id
}
