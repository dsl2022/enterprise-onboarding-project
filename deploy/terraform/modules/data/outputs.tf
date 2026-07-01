output "endpoint" {
  description = "Hostname of the Postgres instance (no port)."
  value       = aws_db_instance.this.address
}

output "port" {
  value = aws_db_instance.this.port
}

output "db_name" {
  value = aws_db_instance.this.db_name
}

output "master_user_secret_arn" {
  description = "ARN of the RDS-managed master-user secret (CMK-encrypted JSON: {username,password,...})."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "security_group_id" {
  value = aws_security_group.db.id
}

output "resource_id" {
  description = "RDS DbiResourceId (db-XXXX). Used to build the rds-db:connect ARN for IAM database auth."
  value       = aws_db_instance.this.resource_id
}
