variable "name_prefix" {
  type        = string
  description = "Prefix applied to all resource names"
}

variable "environment" {
  type        = string
  description = "Deployment environment"
}

variable "aws_region" {
  type        = string
  description = "AWS region"
}

variable "vpc_id" {
  type        = string
  description = "VPC ID"
}

variable "subnet_ids" {
  type        = list(string)
  description = "Private subnet IDs for ECS tasks"
}

variable "security_group_id" {
  type        = string
  description = "Security group ID for ECS tasks"
}

variable "charging_session_image" {
  type        = string
  description = "Full ECR image URI for charging-session-service (e.g. 123456.dkr.ecr.region.amazonaws.com/name:tag)"
}

variable "notification_image" {
  type        = string
  description = "Full ECR image URI for notification-service"
}

variable "target_group_arn" {
  type        = string
  description = "ALB target group ARN for charging-session-service"
}

# charging-session-service config
variable "db_host" {
  type        = string
  description = "RDS hostname"
}

variable "db_port" {
  type        = number
  description = "RDS port"
  default     = 5432
}

variable "db_name" {
  type        = string
  description = "Database name"
}

variable "db_username" {
  type        = string
  description = "Database username"
}

variable "db_password_secret_arn" {
  type        = string
  description = "Secrets Manager ARN for the RDS password"
}

variable "sns_topic_arn" {
  type        = string
  description = "SNS topic ARN for domain events"
}

variable "sqs_queue_url" {
  type        = string
  description = "SQS queue URL for notification events"
}

variable "dynamodb_table_name" {
  type        = string
  description = "DynamoDB notifications table name"
}

variable "ses_from_address" {
  type        = string
  description = "SES verified sender address"
}

variable "cognito_jwks_uri" {
  type        = string
  description = "Cognito JWKS URI for JWT validation"
}

variable "task_cpu" {
  type        = number
  description = "vCPU units for each task (256 = 0.25 vCPU)"
  default     = 256
}

variable "task_memory" {
  type        = number
  description = "Memory for each task in MiB"
  default     = 512
}

variable "desired_count" {
  type        = number
  description = "Desired number of running tasks per service"
  default     = 1
}
