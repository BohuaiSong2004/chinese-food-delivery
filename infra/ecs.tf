resource "aws_ecs_cluster" "main" {
  name = local.name
  setting {
    name  = "containerInsights"
    value = "enhanced"
  }
}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/wokflow/${var.environment}/api"
  retention_in_days = 365
  kms_key_id        = aws_kms_key.main.arn
}

resource "aws_iam_role" "task_execution" {
  name = "${local.name}-task-execution"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "ecs-tasks.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "task_execution" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# The execution role reads exactly the secrets the task definition references, nothing else.
resource "aws_iam_role_policy" "task_secrets" {
  role = aws_iam_role.task_execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = [
          aws_secretsmanager_secret.db_password.arn, aws_secretsmanager_secret.redis_token.arn,
          aws_secretsmanager_secret.es_password.arn, aws_secretsmanager_secret.jwt_key.arn,
          aws_secretsmanager_secret.payment_secret_key.arn, aws_secretsmanager_secret.payment_webhook_secret.arn,
        ]
      },
      { Effect = "Allow", Action = ["kms:Decrypt"], Resource = [aws_kms_key.main.arn] }
    ]
  })
}

# The application itself calls no AWS APIs.
resource "aws_iam_role" "task" {
  name = "${local.name}-task"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "ecs-tasks.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

locals {
  api_env = {
    DB_URL                        = "jdbc:postgresql://${aws_db_instance.main.address}:5432/wokflow?sslmode=require"
    DB_USER                       = "wokflow"
    REDIS_HOST                    = aws_elasticache_replication_group.main.primary_endpoint_address
    SPRING_DATA_REDIS_SSL_ENABLED = "true"
    ES_URL                        = ec_deployment.search.elasticsearch.https_endpoint
    SPRING_ELASTICSEARCH_USERNAME = ec_deployment.search.elasticsearch_username
    PUBLIC_ORIGIN                 = "https://${var.domain_name}"
    MOCKPAY_URL                   = var.payment_provider_url
    REFRESH_COOKIE_SECURE         = "true"
    FORWARD_HEADERS_STRATEGY      = "native"
    SEED_DEMO                     = "false"
    JAVA_OPTS                     = "-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
  }
  api_secrets = {
    DB_PASSWORD                   = aws_secretsmanager_secret.db_password.arn
    SPRING_DATA_REDIS_PASSWORD    = aws_secretsmanager_secret.redis_token.arn
    SPRING_ELASTICSEARCH_PASSWORD = aws_secretsmanager_secret.es_password.arn
    JWT_PRIVATE_KEY               = aws_secretsmanager_secret.jwt_key.arn
    MOCKPAY_SECRET_KEY            = aws_secretsmanager_secret.payment_secret_key.arn
    MOCKPAY_WEBHOOK_SECRET        = aws_secretsmanager_secret.payment_webhook_secret.arn
  }
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 1024
  memory                   = 2048
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  container_definitions = jsonencode([{
    name                   = "api"
    image                  = var.api_image
    essential              = true
    readonlyRootFilesystem = true
    portMappings           = [{ containerPort = 8080, protocol = "tcp" }]
    environment            = [for k, v in local.api_env : { name = k, value = v }]
    secrets                = [for k, v in local.api_secrets : { name = k, valueFrom = v }]
    mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp" }]
    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q UP"]
      interval    = 15
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.api.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "api"
      }
    }
  }])
  volume {
    name = "tmp"
  }
}

resource "aws_ecs_service" "api" {
  name                              = "api"
  cluster                           = aws_ecs_cluster.main.id
  task_definition                   = aws_ecs_task_definition.api.arn
  desired_count                     = var.api_desired_count
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = 90
  propagate_tags                    = "SERVICE"
  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.api.id]
    assign_public_ip = false
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  lifecycle {
    ignore_changes = [desired_count] # owned by autoscaling
  }
}

resource "aws_appautoscaling_target" "api" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.api.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.api_desired_count
  max_capacity       = var.api_max_count
}

resource "aws_appautoscaling_policy" "api_cpu" {
  name               = "${local.name}-api-cpu"
  service_namespace  = aws_appautoscaling_target.api.service_namespace
  resource_id        = aws_appautoscaling_target.api.resource_id
  scalable_dimension = aws_appautoscaling_target.api.scalable_dimension
  policy_type        = "TargetTrackingScaling"
  target_tracking_scaling_policy_configuration {
    target_value = 55 # the load test saturates near 100% CPU; scale out well before the queueing knee
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}

resource "aws_sns_topic" "alarms" {
  name              = "${local.name}-alarms"
  kms_master_key_id = aws_kms_key.main.id
}

resource "aws_sns_topic_subscription" "alarms" {
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

resource "aws_cloudwatch_metric_alarm" "api_5xx" {
  alarm_name          = "${local.name}-api-5xx"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  dimensions          = { LoadBalancer = aws_lb.main.arn_suffix, TargetGroup = aws_lb_target_group.api.arn_suffix }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 5
  threshold           = 25
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
}

resource "aws_ecr_repository" "api" {
  name                 = "wokflow-api"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = aws_kms_key.main.arn
  }
}
