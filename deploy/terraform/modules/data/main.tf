# Phase 2 data layer: RDS Postgres (pgvector enabled by a Flyway migration, not a param group) in the
# private subnets, reachable only from the app task SG. The master password is RDS-managed (never in TF
# state) and pinned to the project CMK so ECS secret injection works with the exec role's existing grant.

resource "aws_db_subnet_group" "this" {
  name       = "${var.name_prefix}-db"
  subnet_ids = var.private_subnet_ids
  tags       = { Name = "${var.name_prefix}-db" }
}

# Postgres ingress from the app tasks only. RDS never initiates connections, so no egress rule.
resource "aws_security_group" "db" {
  name        = "${var.name_prefix}-db-sg"
  description = "Postgres ingress from the app task SG only"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Postgres from app tasks"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [var.task_security_group_id]
  }

  tags = { Name = "${var.name_prefix}-db-sg" }
}

resource "aws_db_instance" "this" {
  identifier        = "${var.name_prefix}-pg"
  engine            = "postgres"
  engine_version    = var.engine_version
  instance_class    = var.instance_class
  allocated_storage = var.allocated_storage
  storage_type      = "gp3"
  storage_encrypted = true
  kms_key_id        = var.kms_key_arn

  db_name  = var.db_name
  username = "eopadmin"
  # RDS generates + rotates the master password into Secrets Manager — it never touches TF state.
  # Pin it to the project CMK (NOT the aws/secretsmanager default key) so the ECS execution role's
  # existing kms:Decrypt grant on this CMK can decrypt it during secret injection.
  manage_master_user_password   = true
  master_user_secret_kms_key_id = var.kms_key_arn

  # Phase 10-1 (ADR-0026): allow RDS IAM database auth so the app's least-privilege runtime role (`eop_app`,
  # created in Flyway V10) can log in with a short-lived IAM token instead of a stored password. Master-user
  # auth (Flyway/migrator) is unaffected. Enabling is a no-downtime modify.
  iam_database_authentication_enabled = true

  multi_az               = var.multi_az
  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.db.id]

  # Dev: disposable data, clean teardown via infra-destroy.
  skip_final_snapshot        = true
  deletion_protection        = false
  apply_immediately          = true
  auto_minor_version_upgrade = true

  tags = { Name = "${var.name_prefix}-pg" }
}
