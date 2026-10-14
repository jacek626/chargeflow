variable "name_prefix" {
  type        = string
  description = "Prefix applied to all resource names"
}

variable "environment" {
  type        = string
  description = "Deployment environment"
}

variable "image_count_to_keep" {
  type        = number
  description = "Number of most-recent images to retain per repository"
  default     = 10
}
