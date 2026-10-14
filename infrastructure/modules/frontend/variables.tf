variable "name_prefix" {
  type        = string
  description = "Prefix applied to all resource names"
}

variable "environment" {
  type        = string
  description = "Deployment environment"
}

variable "acm_certificate_arn" {
  type        = string
  description = "ACM certificate ARN for CloudFront HTTPS (must be in us-east-1; empty string disables custom domain)"
  default     = ""
}

variable "domain_aliases" {
  type        = list(string)
  description = "Custom domain aliases for the CloudFront distribution (e.g. [\"app.chargeflow.dev\"])"
  default     = []
}
