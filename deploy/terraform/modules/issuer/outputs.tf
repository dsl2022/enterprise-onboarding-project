output "issuer_url" {
  description = "https://<cloudfront-domain> — the value used for iss and the Entra federated credential."
  value       = local.issuer_url
}

output "issuer_bucket" {
  description = "S3 bucket holding the discovery doc + jwks.json (app writes jwks at runtime)."
  value       = aws_s3_bucket.issuer.id
}

output "signing_secret_name" {
  value = aws_secretsmanager_secret.signing_key.name
}

output "signing_secret_arn" {
  value = aws_secretsmanager_secret.signing_key.arn
}

output "task_access_policy_arn" {
  description = "Attach to the ECS task role in Phase 4."
  value       = aws_iam_policy.task_access.arn
}

output "cloudfront_distribution_id" {
  value = aws_cloudfront_distribution.issuer.id
}
