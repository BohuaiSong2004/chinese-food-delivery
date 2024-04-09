data "aws_caller_identity" "current" {}

resource "aws_kms_key" "main" {
  description         = "${local.name} data, logs and secrets"
  enable_key_rotation = true
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AccountAdmin"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        Sid       = "CloudWatchLogs"
        Effect    = "Allow"
        Principal = { Service = "logs.${var.region}.amazonaws.com" }
        Action    = ["kms:Encrypt*", "kms:Decrypt*", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:Describe*"]
        Resource  = "*"
      }
    ]
  })
}

resource "random_password" "db" {
  length  = 32
  special = false
}

resource "random_password" "redis" {
  length  = 48
  special = false
}

resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_parameter_group" "pg" {
  name   = "${local.name}-pg18"
  family = "postgres18"
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }
}

resource "aws_iam_role" "rds_monitoring" {
  name = "${local.name}-rds-monitoring"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "monitoring.rds.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

resource "aws_db_instance" "main" {
  identifier                          = local.name
  engine                              = "postgres"
  engine_version                      = "18"
  instance_class                      = var.db_instance_class
  allocated_storage                   = 50
  max_allocated_storage               = 500
  storage_type                        = "gp3"
  storage_encrypted                   = true
  kms_key_id                          = aws_kms_key.main.arn
  db_name                             = "wokflow"
  username                            = "wokflow"
  password                            = random_password.db.result
  db_subnet_group_name                = aws_db_subnet_group.main.name
  vpc_security_group_ids              = [aws_security_group.db.id]
  parameter_group_name                = aws_db_parameter_group.pg.name
  multi_az                            = true
  backup_retention_period             = 14
  copy_tags_to_snapshot               = true
  deletion_protection                 = true
  skip_final_snapshot                 = false
  final_snapshot_identifier           = "${local.name}-final"
  auto_minor_version_upgrade          = true
  iam_database_authentication_enabled = true
  performance_insights_enabled        = true
  performance_insights_kms_key_id     = aws_kms_key.main.arn
  monitoring_interval                 = 30
  monitoring_role_arn                 = aws_iam_role.rds_monitoring.arn
  enabled_cloudwatch_logs_exports     = ["postgresql", "upgrade"]
  publicly_accessible                 = false
}

resource "aws_elasticache_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

# Hot-menu counters, menu cache, rate limits and the live-status pub/sub channel.
# AOF is not available on ElastiCache, which is fine: the hot-menu counters rebuild themselves from the ledger.
resource "aws_elasticache_replication_group" "main" {
  replication_group_id       = local.name
  description                = "Wokflow cache, counters and pub/sub"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = var.redis_node_type
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  multi_az_enabled           = true
  subnet_group_name          = aws_elasticache_subnet_group.main.name
  security_group_ids         = [aws_security_group.redis.id]
  at_rest_encryption_enabled = true
  kms_key_id                 = aws_kms_key.main.arn
  transit_encryption_enabled = true
  auth_token                 = random_password.redis.result
  snapshot_retention_limit   = 3
  auto_minor_version_upgrade = true
}

data "ec_stack" "es" {
  version_regex = "9.5.?"
  region        = var.elastic_cloud_region
}

resource "ec_deployment" "search" {
  name                   = local.name
  region                 = var.elastic_cloud_region
  version                = data.ec_stack.es.version
  deployment_template_id = "aws-storage-optimized"
  elasticsearch = {
    hot = {
      size        = "4g"
      zone_count  = 2
      autoscaling = {}
    }
  }
  traffic_filter = [ec_deployment_traffic_filter.nat.id]
}

# Only the NAT gateways' addresses (i.e. the API tasks) may reach the cluster.
resource "ec_deployment_traffic_filter" "nat" {
  name   = "${local.name}-nat"
  region = var.elastic_cloud_region
  type   = "ip"
  dynamic "rule" {
    for_each = aws_eip.nat[*].public_ip
    content {
      source = "${rule.value}/32"
    }
  }
}
