output "primary_endpoint" {
  description = "Primary endpoint hostname for writes (Spring Session connects here)."
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "port" {
  value = aws_elasticache_replication_group.this.port
}

output "security_group_id" {
  value = aws_security_group.redis.id
}
