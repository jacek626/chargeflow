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
