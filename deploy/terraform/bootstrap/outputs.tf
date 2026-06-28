# With manage_github=true these ARNs are wired into GitHub secrets automatically.
# Otherwise, copy them in by hand (see README).

output "oidc_provider_arn" {
  description = "GitHub OIDC provider ARN (created here only if create_oidc_provider=true; otherwise the existing account one)."
  value       = local.oidc_provider_arn
}

output "plan_role_arn" {
  value = aws_iam_role.plan.arn
}

output "apply_role_arn" {
  value = aws_iam_role.apply.arn
}

output "deploy_role_arn" {
  value = aws_iam_role.deploy.arn
}

output "state_bucket" {
  description = "Wire into ../envs/<env>.backend.hcl (already set for dev)."
  value       = aws_s3_bucket.state.id
}

output "lock_table" {
  value = aws_dynamodb_table.lock.name
}
