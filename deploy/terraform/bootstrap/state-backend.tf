# Remote state backend for the MAIN stack (../): one S3 bucket + a DynamoDB lock
# table. The main stack's backend.tf points here via ../envs/<env>.backend.hcl,
# so these names MUST match that file:
#   bucket         = eop-tfstate-<env>-<owner>   (local.state_bucket below)
#   dynamodb_table = var.lock_table_name
#
# Replaces the manual `aws s3api ...` / `aws dynamodb ...` steps in RUNBOOK section 1a.

locals {
  state_bucket = "eop-tfstate-${var.env_name}-${var.github_owner}" # -> eop-tfstate-dev-dsl2022
}

resource "aws_s3_bucket" "state" {
  bucket = local.state_bucket

  # This is an ephemeral demo backend you intend to tear down: allow `terraform
  # destroy` to remove the bucket even though it is versioned (otherwise S3 refuses
  # to delete a non-empty/versioned bucket). NOTE: this deletes the main stack's
  # Terraform state — only relevant at teardown, after the main stack is already gone.
  force_destroy = true
}

# Recover from a corrupt/rolled-back state write.
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Encrypt at rest (AWS-managed aws/s3 KMS key).
resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
  }
}

# State can hold sensitive values — block all public access.
resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Reject any non-TLS request.
data "aws_iam_policy_document" "state_tls_only" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.state.arn, "${aws_s3_bucket.state.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = data.aws_iam_policy_document.state_tls_only.json
}

# Terraform's S3 backend uses this table for state LOCKING. LockID is the fixed
# key name the backend expects. PAY_PER_REQUEST: lock traffic is tiny + bursty.
resource "aws_dynamodb_table" "lock" {
  name         = var.lock_table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }
}
