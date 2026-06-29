locals {
  has_image = var.app_image != ""
}

resource "aws_ecs_cluster" "this" {
  name = var.name_prefix
}

# Flow 1 confidential-client secret, CMK-encrypted, injected into the task at start.
resource "aws_secretsmanager_secret" "flow1" {
  name                    = "${var.name_prefix}/flow1-client-secret"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "flow1" {
  count         = var.create_flow1_secret ? 1 : 0
  secret_id     = aws_secretsmanager_secret.flow1.id
  secret_string = var.entra_client_secret
}

# ---- IAM ----
data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Execution role: pull image, ship logs, read the Flow 1 secret for injection.
resource "aws_iam_role" "execution" {
  name               = "${var.name_prefix}-exec"
  assume_role_policy = data.aws_iam_policy_document.assume.json
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "exec_secrets" {
  statement {
    # Flow 1 client secret + the RDS-managed DB master secret. Both are CMK-encrypted, so the
    # kms:Decrypt grant below (project CMK) covers injection of both at task start.
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.flow1.arn, var.db_master_secret_arn]
  }
  statement {
    actions   = ["kms:Decrypt"]
    resources = [var.kms_key_arn]
  }
}

resource "aws_iam_role_policy" "exec_secrets" {
  name   = "flow1-secret-read"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.exec_secrets.json
}

# Task role: the app's runtime identity. Gets the issuer access policy (publish jwks,
# read/create signing key, use CMK). WIF needs no other cloud creds.
resource "aws_iam_role" "task" {
  name               = "${var.name_prefix}-task"
  assume_role_policy = data.aws_iam_policy_document.assume.json
}

resource "aws_iam_role_policy_attachment" "task_issuer" {
  role       = aws_iam_role.task.name
  policy_arn = var.issuer_task_access_policy_arn
}

# ---- Task definition + service (only once a real image exists) ----
resource "aws_ecs_task_definition" "app" {
  count                    = local.has_image ? 1 : 0
  family                   = "${var.name_prefix}-app"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([
    {
      name         = "app"
      image        = var.app_image
      essential    = true
      portMappings = [{ containerPort = var.container_port, protocol = "tcp" }]
      environment = concat([
        { name = "SPRING_PROFILES_ACTIVE", value = "auth,data" },
        { name = "WIF_ENABLED", value = "true" },
        { name = "WIF_ISSUER_HOST", value = var.wif_issuer_host },
        { name = "WIF_ISSUER_BUCKET", value = var.wif_issuer_bucket },
        { name = "WIF_SIGNING_SECRET_NAME", value = var.wif_signing_secret_name },
        { name = "WIF_SUBJECT", value = var.workload_subject },
        { name = "AWS_REGION", value = var.region },
        { name = "ENTRA_TENANT_ID", value = var.entra_tenant_id },
        { name = "ENTRA_APP_CLIENT_ID", value = var.entra_app_client_id },
        { name = "APP_BASE_URL", value = var.app_url },
        { name = "APP_REDIRECT_URI", value = "${var.app_url}/login/oauth2/code/entra" },
        # Phase 2: persistence + session endpoints (non-secret coordinates only).
        { name = "DB_HOST", value = var.db_host },
        { name = "DB_PORT", value = tostring(var.db_port) },
        { name = "DB_NAME", value = var.db_name },
        { name = "REDIS_HOST", value = var.redis_host },
        { name = "REDIS_PORT", value = tostring(var.redis_port) },
        ],
        # Phase 4b: real Entra app-registration provisioning. Off by default (SimulatedProvisioner stays in
        # charge → no Graph call) so this can be flipped ONLY AFTER a Global Admin grants admin consent for
        # Application.ReadWrite.OwnedBy — a token minted before consent would 403 until its cache expires.
        var.provisioning_real ? [
          { name = "EOP_PROVISIONING_SIMULATE", value = "false" },
          { name = "EOP_PROVISIONING_SCHEDULER", value = "true" },
      ] : [])
      secrets = [
        { name = "ENTRA_CLIENT_SECRET", valueFrom = aws_secretsmanager_secret.flow1.arn },
        # DB creds pulled from the RDS-managed secret JSON by key (ECS supports the :json-key:: suffix).
        { name = "DB_USERNAME", valueFrom = "${var.db_master_secret_arn}:username::" },
        { name = "DB_PASSWORD", valueFrom = "${var.db_master_secret_arn}:password::" },
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = var.log_group_name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "app"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "app" {
  count           = local.has_image ? 1 : 0
  name            = var.name_prefix
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.app[0].arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.task_security_group_id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = "app"
    container_port   = var.container_port
  }

  # Generous grace: on the first Phase-2 roll RDS may still be finishing init when tasks start. The app
  # retries the JDBC/Flyway connection on boot; this keeps the LB from killing tasks during that window.
  health_check_grace_period_seconds = 300
}
