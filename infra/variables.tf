variable "region" {
  description = "AWS region for the stack."
  type        = string
  default     = "us-west-2"
}

variable "availability_zones" {
  description = "Two availability zones in the region (pinned, so the subnet layout never shifts)."
  type        = list(string)
  default     = ["us-west-2a", "us-west-2b"]
}

variable "environment" {
  description = "Environment name, used in resource names."
  type        = string
  default     = "prod"
}

variable "domain_name" {
  description = "Public hostname served by CloudFront, e.g. wokflow.example.com."
  type        = string
}

variable "hosted_zone_id" {
  description = "Route 53 hosted zone that contains domain_name (used for DNS validation and the alias record)."
  type        = string
}

variable "api_image" {
  description = "API image URI with an immutable tag or digest, e.g. <account>.dkr.ecr.<region>.amazonaws.com/wokflow-api:sha-abc123."
  type        = string
}

variable "api_desired_count" {
  description = "API tasks to run normally; autoscaling moves between min and max."
  type        = number
  default     = 2
}

variable "api_max_count" {
  description = "Upper bound for API autoscaling."
  type        = number
  default     = 8
}

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.medium"
}

variable "redis_node_type" {
  description = "ElastiCache node type."
  type        = string
  default     = "cache.t4g.small"
}

variable "elastic_cloud_region" {
  description = "Elastic Cloud region, colocated with the AWS region."
  type        = string
  default     = "aws-us-west-2"
}

variable "payment_provider_url" {
  description = "Base URL of the payment provider API."
  type        = string
}

variable "alarm_email" {
  description = "Address subscribed to operational alarms."
  type        = string
}
