# Prefiks nazw zasobów, np. "chargeflow-dev" — rozróżnia środowiska na jednym koncie.
variable "name_prefix" {
  description = "Resource name prefix"
  type        = string
}

# Nazwa środowiska — trafia do tagu Environment.
variable "environment" {
  description = "Deployment environment"
  type        = string
}
