variable "github_repo" {
  type        = string
  description = "GitHub repository in org/repo format (e.g. acme/chargeflow)"
}

variable "environment" {
  type        = string
  description = "Deployment environment"

  validation {
    condition     = contains(["local", "dev", "prod"], var.environment)
    error_message = "environment must be one of: local, dev, prod"
  }
}

variable "aws_region" {
  type        = string
  description = "AWS region"
  default     = "eu-central-1"
}

variable "project_name" {
  type        = string
  description = "Project name used as prefix for resource names"
  default     = "chargeflow"
}

variable "cognito_callback_urls" {
  type        = list(string)
  description = "OAuth2 redirect URIs for the Angular app"
  default     = ["https://app.chargeflow.dev/auth/callback"]
}

variable "cognito_logout_urls" {
  type        = list(string)
  description = "Logout redirect URIs for the Angular app"
  default     = ["https://app.chargeflow.dev"]
}

variable "ses_from_email" {
  type        = string
  description = "Sender email address verified in SES"
  default     = "noreply@chargeflow.pl"
}

variable "log_retention_days" {
  type        = number
  description = "CloudWatch log retention in days"
  default     = 30
}

# ── Container images ──────────────────────────────────────────────────────────

variable "charging_session_image" {
  type        = string
  description = "Full ECR image URI for charging-session-service (e.g. 123456.dkr.ecr.eu-central-1.amazonaws.com/chargeflow-dev-charging-session-service:latest)"
  default     = "placeholder/charging-session-service:latest"
}

variable "notification_image" {
  type        = string
  description = "Full ECR image URI for notification-service"
  default     = "placeholder/notification-service:latest"
}

# ── ECS sizing ────────────────────────────────────────────────────────────────

variable "ecs_task_cpu" {
  type        = number
  description = "vCPU units for each ECS task (256 = 0.25 vCPU)"
  default     = 256
}

variable "ecs_task_memory" {
  type        = number
  description = "Memory for each ECS task in MiB"
  default     = 512
}

variable "ecs_desired_count" {
  type        = number
  description = "Desired number of running tasks per service"
  default     = 1
}

# ── TLS / Domain ─────────────────────────────────────────────────────────────

variable "acm_certificate_arn" {
  type        = string
  description = "ACM certificate ARN for the ALB HTTPS listener (empty string = HTTP only)"
  default     = ""
}

variable "frontend_acm_certificate_arn" {
  type        = string
  description = "ACM certificate ARN for CloudFront (must be in us-east-1; empty string = CloudFront default cert)"
  default     = ""
}

variable "frontend_domain_aliases" {
  type        = list(string)
  description = "Custom domain aliases for CloudFront (e.g. [\"app.chargeflow.dev\"])"
  default     = []
}
