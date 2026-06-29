output "bucket_name" {
  description = "S3 bucket holding the Angular build (synced under the app/ prefix by frontend-deploy)."
  value       = aws_s3_bucket.spa.id
}

output "bucket_arn" {
  value = aws_s3_bucket.spa.arn
}

output "bucket_regional_domain_name" {
  description = "S3 origin domain for the app distribution."
  value       = aws_s3_bucket.spa.bucket_regional_domain_name
}

output "oac_id" {
  value = aws_cloudfront_origin_access_control.spa.id
}

output "function_arn" {
  description = "SPA-router CloudFront Function ARN (attached to the /app behaviors in edge)."
  value       = aws_cloudfront_function.spa_router.arn
}
