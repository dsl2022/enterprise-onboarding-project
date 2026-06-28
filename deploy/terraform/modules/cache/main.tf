# Phase 2 cache layer: ElastiCache Redis for the BFF session store (Spring Session), so Fargate tasks
# are stateless and Phase 8 can run >=2 of them. Private subnets, reachable only from the app task SG.
#
# DEV IS A SINGLE NODE = a session SPOF, not HA (see var.multi_az). Flip multi_az for a replica +
# automatic failover in prod. Transit encryption is off in dev (SG-isolated); prod flips clients to
# rediss:// when it's enabled.

resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name_prefix}-redis"
  subnet_ids = var.private_subnet_ids
}

resource "aws_security_group" "redis" {
  name        = "${var.name_prefix}-redis-sg"
  description = "Redis ingress from the app task SG only"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Redis from app tasks"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [var.task_security_group_id]
  }

  tags = { Name = "${var.name_prefix}-redis-sg" }
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = "${var.name_prefix}-redis"
  description          = "BFF session store (Spring Session)"

  engine         = "redis"
  engine_version = var.engine_version
  node_type      = var.node_type
  port           = 6379

  # multi_az toggles the whole HA posture together (a lone node can't fail over).
  num_cache_clusters         = var.multi_az ? 2 : 1
  automatic_failover_enabled = var.multi_az
  multi_az_enabled           = var.multi_az

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.redis.id]

  at_rest_encryption_enabled = true
  kms_key_id                 = var.kms_key_arn # project CMK, consistent with RDS storage/secret + logs
  transit_encryption_enabled = false           # dev: SG-isolated; prod flips this + the client to rediss://
  apply_immediately          = true

  tags = { Name = "${var.name_prefix}-redis" }
}
