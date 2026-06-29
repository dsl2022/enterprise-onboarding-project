output "app_url" {
  description = "Public HTTPS base URL of the app (CloudFront)."
  value       = "https://${aws_cloudfront_distribution.app.domain_name}"
}

output "distribution_id" {
  description = "App CloudFront distribution id (frontend-deploy invalidates /app/* on it)."
  value       = aws_cloudfront_distribution.app.id
}

output "target_group_arn" {
  value = aws_lb_target_group.app.arn
}

output "task_security_group_id" {
  value = aws_security_group.task.id
}

output "alb_dns_name" {
  value = aws_lb.this.dns_name
}
