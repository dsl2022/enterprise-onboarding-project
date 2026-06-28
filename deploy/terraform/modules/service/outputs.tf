output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "service_name" {
  value = local.has_image ? aws_ecs_service.app[0].name : null
}

output "flow1_secret_arn" {
  value = aws_secretsmanager_secret.flow1.arn
}
