# Nazwa środowiska: buduje prefiks nazw (chargeflow-local) i tag Environment.
# UWAGA: brak default, a start-local.sh robi apply -input=false bez -var environment -
# zadziała tylko, jeśli istnieje terraform.tfvars (skopiuj terraform.tfvars.example).
variable "environment" {
  type        = string
  description = "Deployment environment"

  # Walidacja: literówka (np. "dve" albo "staging") zatrzyma plan czytelnym błędem,
  # zanim Terraform stworzy zasoby o złych nazwach.
  validation {
    condition     = contains(["local", "dev", "prod"], var.environment) # true = wartość poprawna; contains() sprawdza, czy wartość jest na liście
    error_message = "environment must be one of: local, dev, prod"      # komunikat pokazywany, gdy condition zwróci false
  }
}

# Region - LocalStack przyjmie dowolny; musi zgadzać się z AWS_REGION ustawianym dla serwisów.
variable "aws_region" {
  type        = string
  description = "AWS region"
  default     = "eu-central-1"
}

# Nazwa projektu - pierwsza część prefiksu nazw zasobów (chargeflow-<env>-...).
variable "project_name" {
  type        = string
  description = "Project name used as prefix for resource names"
  default     = "chargeflow"
}
