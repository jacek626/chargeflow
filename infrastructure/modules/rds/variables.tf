variable "name_prefix" {
  type        = string
  description = "Prefix applied to all resource names"
}

variable "environment" {
  type        = string
  description = "Deployment environment"
}

variable "vpc_id" {
  type        = string
  description = "VPC ID"
}

variable "subnet_ids" {
  type        = list(string)
  description = "Private subnet IDs for the DB subnet group"
}

variable "security_group_id" {
  type        = string
  description = "Security group ID for the RDS instance"
}

variable "db_name" {
  type        = string
  description = "Database name"
  default     = "chargeflow"
}

variable "db_username" {
  type        = string
  description = "Database master username"
  default     = "chargeflow"
}

variable "instance_class" {
  type        = string
  description = "RDS instance class"
  default     = "db.t4g.micro"
}

variable "allocated_storage_gb" {
  type        = number
  description = "Allocated storage in GiB"
  default     = 20
}

variable "postgres_version" {
  type        = string
  description = "PostgreSQL engine version"
  default     = "16"
}

variable "deletion_protection" {
  type        = bool
  description = "Enable deletion protection"
  default     = false
}
