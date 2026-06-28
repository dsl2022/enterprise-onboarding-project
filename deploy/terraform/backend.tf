terraform {
  # Partial backend config — bucket/key/region/dynamodb_table supplied at init via:
  #   terraform init -backend-config=envs/<env>.backend.hcl
  # (mirrors the godaddy pattern). Bootstrap creates the bucket + lock table; see RUNBOOK.md.
  backend "s3" {
    encrypt = true
  }
}
