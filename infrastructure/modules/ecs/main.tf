data "aws_caller_identity" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
}

# ── ECS Cluster ──────────────────────────────────────────────────────────────

resource "aws_ecs_cluster" "main" {
  name = "${var.name_prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = { Name = "${var.name_prefix}-cluster" }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

# ── CloudWatch Log Groups ─────────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "charging_session" {
  name              = "/chargeflow/${var.environment}/charging-session-service/ecs"
  retention_in_days = 30

  tags = { Name = "${var.name_prefix}-css-logs" }
}

resource "aws_cloudwatch_log_group" "notification" {
  name              = "/chargeflow/${var.environment}/notification-service/ecs"
  retention_in_days = 30

  tags = { Name = "${var.name_prefix}-ns-logs" }
}

# ── IAM — Task Execution Role ─────────────────────────────────────────────────

resource "aws_iam_role" "execution" {
  name = "${var.name_prefix}-ecs-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name = "read-db-secret"
  role = aws_iam_role.execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [var.db_password_secret_arn]
    }]
  })
}

# ── IAM — Task Role (runtime AWS API access) ──────────────────────────────────

resource "aws_iam_role" "task" {
  name = "${var.name_prefix}-ecs-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy" "task" {
  name = "chargeflow-runtime"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "SNSPublish"
        Effect   = "Allow"
        Action   = ["sns:Publish"]
        Resource = [var.sns_topic_arn]
      },
      {
        Sid    = "SQSConsume"
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes"
        ]
        Resource = ["arn:aws:sqs:${var.aws_region}:${local.account_id}:*"]
      },
      {
        Sid    = "DynamoDB"
        Effect = "Allow"
        Action = [
          "dynamodb:PutItem",
          "dynamodb:GetItem",
          "dynamodb:Query",
          "dynamodb:UpdateItem"
        ]
        Resource = ["arn:aws:dynamodb:${var.aws_region}:${local.account_id}:table/*"]
      },
      {
        Sid      = "SESSend"
        Effect   = "Allow"
        Action   = ["ses:SendEmail", "ses:SendRawEmail"]
        Resource = ["*"]
      },
      {
        Sid    = "CloudWatchMetrics"
        Effect = "Allow"
        Action = [
          "cloudwatch:PutMetricData",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = ["*"]
      }
    ]
  })
}

# ── Task Definition — charging-session-service ────────────────────────────────

resource "aws_ecs_task_definition" "charging_session" {
  family                   = "${var.name_prefix}-charging-session-service"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([
    {
      name      = "charging-session-service"
      image     = var.charging_session_image
      essential = true

      portMappings = [{ containerPort = 8080, protocol = "tcp" }]

      environment = [
        { name = "AWS_REGION", value = var.aws_region },
        { name = "DB_URL", value = "jdbc:postgresql://${var.db_host}:${var.db_port}/${var.db_name}" },
        { name = "DB_USERNAME", value = var.db_username },
        { name = "SNS_ENABLED", value = "true" },
        { name = "SNS_TOPIC_ARN", value = var.sns_topic_arn },
        { name = "COGNITO_JWKS_URI", value = var.cognito_jwks_uri }
      ]

      secrets = [
        { name = "DB_PASSWORD", valueFrom = var.db_password_secret_arn }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.charging_session.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }

      healthCheck = {
        command     = ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 60
      }
    }
  ])

  tags = { Name = "${var.name_prefix}-charging-session-service" }
}

# ── Task Definition — notification-service ───────────────────────────────────

resource "aws_ecs_task_definition" "notification" {
  family                   = "${var.name_prefix}-notification-service"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([
    {
      name      = "notification-service"
      image     = var.notification_image
      essential = true

      portMappings = [{ containerPort = 8081, protocol = "tcp" }]

      environment = [
        { name = "AWS_REGION", value = var.aws_region },
        { name = "SQS_ENABLED", value = "true" },
        { name = "SQS_QUEUE_URL", value = var.sqs_queue_url },
        { name = "DYNAMODB_NOTIFICATIONS_TABLE", value = var.dynamodb_table_name },
        { name = "SES_ENABLED", value = "true" },
        { name = "SES_FROM_ADDRESS", value = var.ses_from_address },
        { name = "SNS_ENABLED", value = "true" },
        { name = "SNS_NOTIFICATION_TOPIC_ARN", value = var.sns_topic_arn },
        { name = "COGNITO_JWKS_URI", value = var.cognito_jwks_uri }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.notification.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }

      healthCheck = {
        command     = ["CMD-SHELL", "curl -f http://localhost:8081/actuator/health || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 60
      }
    }
  ])

  tags = { Name = "${var.name_prefix}-notification-service" }
}

# ── ECS Services ──────────────────────────────────────────────────────────────

resource "aws_ecs_service" "charging_session" {
  name            = "${var.name_prefix}-charging-session-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.charging_session.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [var.security_group_id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = "charging-session-service"
    container_port   = 8080
  }

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  lifecycle {
    ignore_changes = [task_definition]
  }

  tags = { Name = "${var.name_prefix}-charging-session-service" }
}

resource "aws_ecs_service" "notification" {
  name            = "${var.name_prefix}-notification-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.notification.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [var.security_group_id]
    assign_public_ip = false
  }

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  lifecycle {
    ignore_changes = [task_definition]
  }

  tags = { Name = "${var.name_prefix}-notification-service" }
}
