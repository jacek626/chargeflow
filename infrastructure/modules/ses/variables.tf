variable "from_email" {
  description = "Email address to verify in SES (used as notification sender)"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}
