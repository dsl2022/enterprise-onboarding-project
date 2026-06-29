# ---------------------------------------------------------------------------
# webapp: storage + edge identity for the Angular SPA served at /app.
#
# This module owns the bucket, its OAC, and the SPA-fallback CloudFront Function.
# It deliberately does NOT own a distribution — the SPA is an ADDITIONAL origin +
# behavior on the EXISTING app distribution (the `edge` module), so /app is
# same-origin with the BFF and the session cookie just works. The edge module
# wires the origin/behavior and the bucket policy (it owns the distribution ARN).
#
# Coexistence: this is purely additive. The legacy HTML stays the default route
# (/ -> ALB); /app -> this bucket. A future cutover flips the default behavior —
# a separate, reversible change.
# ---------------------------------------------------------------------------

data "aws_caller_identity" "current" {}

locals {
  bucket_name = "${var.name_prefix}-webapp-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket" "spa" {
  bucket        = local.bucket_name
  force_destroy = true # static build is reproducible from CI; safe to drop on teardown
}

resource "aws_s3_bucket_public_access_block" "spa" {
  bucket                  = aws_s3_bucket.spa.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "spa" {
  bucket = aws_s3_bucket.spa.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256" # public static assets — no CMK needed
    }
  }
}

# Only the app CloudFront distribution may read objects (granted via the bucket
# policy in the edge module, scoped by AWS:SourceArn).
resource "aws_cloudfront_origin_access_control" "spa" {
  name                              = "${var.name_prefix}-webapp-oac"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_function" "spa_router" {
  name    = "${var.name_prefix}-spa-router"
  runtime = "cloudfront-js-2.0"
  comment = "SPA deep-link fallback for /app"
  publish = true
  code    = file("${path.module}/spa-router.js")
}
