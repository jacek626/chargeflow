# Wspólny prefiks nazw zasobów (np. chargeflow-dev).
variable "name_prefix" {
  type        = string
  description = "Prefix applied to all resource names"
}

# Nazwa środowiska (local/dev).
variable "environment" {
  type        = string
  description = "Deployment environment"
}

# ARN certyfikatu ACM dla własnej domeny; CloudFront wymaga certyfikatu z regionu us-east-1.
variable "acm_certificate_arn" {
  type        = string
  description = "ACM certificate ARN for CloudFront HTTPS (must be in us-east-1; empty string disables custom domain)"
  default     = "" # pusty => domyślny certyfikat *.cloudfront.net
}

# Własne domeny dystrybucji (CNAME); działają tylko razem z acm_certificate_arn.
variable "domain_aliases" {
  type        = list(string)
  description = "Custom domain aliases for the CloudFront distribution (e.g. [\"app.chargeflow.dev\"])"
  default     = []
}
