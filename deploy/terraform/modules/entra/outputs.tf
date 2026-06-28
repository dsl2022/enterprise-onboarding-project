output "app_client_id" {
  description = "Entra application (client) id — used for admin consent and as the app's client_id in the WIF exchange."
  value       = azuread_application.app.application_id
}

output "app_object_id" {
  value = azuread_application.app.object_id
}

output "service_principal_object_id" {
  value = azuread_service_principal.app.object_id
}

output "workload_subject" {
  description = "Subject the workload must put in its assertion (also the app's WIF_SUBJECT env in Phase 4)."
  value       = var.workload_subject
}

output "client_secret_value" {
  description = "Flow 1 confidential-client secret (null unless create_client_secret=true). Caller stores it in Secrets Manager."
  value       = var.create_client_secret ? azuread_application_password.flow1[0].value : null
  sensitive   = true
}
