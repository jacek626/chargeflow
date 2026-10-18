# Zmienne wejściowe modułu alb.
# Prefiks nazw zasobów (np. "chargeflow-dev").
variable "name_prefix" {
  type        = string
  description = "Prefix applied to all resource names"
}

# Nazwa środowiska.
# UWAGA: zmienna zadeklarowana, ale nieużywana w modules/alb/main.tf.
variable "environment" {
  type        = string
  description = "Deployment environment"
}

# VPC, w której jest target group (z outputu modułu network).
variable "vpc_id" {
  type        = string
  description = "VPC ID"
}

# Subnety publiczne dla węzłów ALB (min. 2 w różnych AZ).
variable "public_subnet_ids" {
  type        = list(string)
  description = "Public subnet IDs for the ALB"
}

# SG ALB z modułu network (80/443 z internetu).
variable "security_group_id" {
  type        = string
  description = "Security group ID for the ALB"
}

# ARN certyfikatu ACM; pusty string = tylko HTTP (domyślnie w dev). Podanie ARN włącza listener 443 i redirect 80 -> 443.
variable "acm_certificate_arn" {
  type        = string
  description = "ACM certificate ARN for HTTPS listener (empty string to use HTTP only)"
  default     = ""
}
