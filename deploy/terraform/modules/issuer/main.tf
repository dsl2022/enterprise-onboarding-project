data "aws_caller_identity" "current" {}

locals {
  bucket_name = "${var.name_prefix}-issuer-${data.aws_caller_identity.current.account_id}"
}

# ---------------------------------------------------------------------------
# Private bucket that holds the OIDC issuer documents. Never public — served
# only through CloudFront via Origin Access Control (OAC).
# ---------------------------------------------------------------------------
resource "aws_s3_bucket" "issuer" {
  bucket        = local.bucket_name
  force_destroy = true # cost-teardown: discovery/jwks are reproducible by the app
}

resource "aws_s3_bucket_public_access_block" "issuer" {
  bucket                  = aws_s3_bucket.issuer.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "issuer" {
  bucket = aws_s3_bucket.issuer.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256" # public JWKS/discovery — no CMK needed
    }
  }
}

# ---------------------------------------------------------------------------
# CloudFront distribution (default *.cloudfront.net domain = our issuer host).
# CachingDisabled so signing-key rotation propagates immediately.
# ---------------------------------------------------------------------------
resource "aws_cloudfront_origin_access_control" "issuer" {
  name                              = "${var.name_prefix}-issuer-oac"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_distribution" "issuer" {
  enabled         = true
  is_ipv6_enabled = true
  comment         = "${var.name_prefix} workload OIDC issuer"
  price_class     = "PriceClass_100"

  origin {
    domain_name              = aws_s3_bucket.issuer.bucket_regional_domain_name
    origin_id                = "issuer-s3"
    origin_access_control_id = aws_cloudfront_origin_access_control.issuer.id
  }

  default_cache_behavior {
    target_origin_id       = "issuer-s3"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad" # Managed-CachingDisabled
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true # serves the *.cloudfront.net domain over HTTPS
  }
}

# Bucket policy: only this CloudFront distribution may read objects (OAC).
data "aws_iam_policy_document" "bucket" {
  statement {
    sid       = "AllowCloudFrontOAC"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.issuer.arn}/*"]
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.issuer.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "issuer" {
  bucket = aws_s3_bucket.issuer.id
  policy = data.aws_iam_policy_document.bucket.json
}

# ---------------------------------------------------------------------------
# OIDC discovery document. `issuer` must EXACTLY equal https://<issuer-host>.
# Published by Terraform (deterministic, key-independent). The app publishes
# .well-known/jwks.json at runtime (it owns the signing key).
# ---------------------------------------------------------------------------
locals {
  issuer_url = "https://${aws_cloudfront_distribution.issuer.domain_name}"
}

resource "aws_s3_object" "discovery" {
  bucket       = aws_s3_bucket.issuer.id
  key          = ".well-known/openid-configuration"
  content_type = "application/json"
  content = jsonencode({
    issuer                                = local.issuer_url
    jwks_uri                              = "${local.issuer_url}/.well-known/jwks.json"
    response_types_supported              = ["id_token"]
    subject_types_supported               = ["public"]
    id_token_signing_alg_values_supported = ["RS256"]
  })
}

# ---------------------------------------------------------------------------
# Signing secret — created EMPTY. The app generates the RSA key on first boot
# and writes the first version (private key never enters Terraform state).
# ---------------------------------------------------------------------------
resource "aws_secretsmanager_secret" "signing_key" {
  name                    = "${var.name_prefix}/issuer-signing-key"
  description             = "RSA private key (JWK JSON) for the ${var.name_prefix} workload OIDC issuer"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = 0 # immediate delete on teardown
}

# ---------------------------------------------------------------------------
# IAM policy the app's ECS task role attaches to (in Phase 4): publish jwks,
# read/create the signing key, use the CMK.
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "task_access" {
  statement {
    sid       = "PublishIssuerDocs"
    effect    = "Allow"
    actions   = ["s3:PutObject", "s3:GetObject"]
    resources = ["${aws_s3_bucket.issuer.arn}/*"]
  }
  statement {
    sid       = "ManageSigningKey"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:PutSecretValue", "secretsmanager:DescribeSecret"]
    resources = [aws_secretsmanager_secret.signing_key.arn]
  }
  statement {
    sid       = "UseCmk"
    effect    = "Allow"
    actions   = ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey"]
    resources = [var.kms_key_arn]
  }
}

resource "aws_iam_policy" "task_access" {
  name   = "${var.name_prefix}-issuer-access"
  policy = data.aws_iam_policy_document.task_access.json
}
