variable "name_prefix" {
  type        = string
  description = "Prefix applied to all resource names"
}

variable "environment" {
  type        = string
  description = "Deployment environment"
}

variable "sqs_visibility_timeout_seconds" {
  type        = number
  description = "SQS visibility timeout in seconds"
  default     = 30
}

variable "dlq_max_receive_count" {
  type        = number
  description = "Max receive count before a message moves to the DLQ"
  default     = 3
}

variable "message_retention_seconds" {
  type        = number
  description = "SQS message retention period in seconds"
  default     = 86400
}
