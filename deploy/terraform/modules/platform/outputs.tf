output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

output "kms_key_arn" {
  value = aws_kms_key.this.arn
}

output "log_group_name" {
  value = aws_cloudwatch_log_group.app.name
}
