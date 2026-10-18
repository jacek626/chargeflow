# Nazwa środowiska: buduje prefiks nazw (chargeflow-prod) i tag Environment.
# Brak default - podaj przez terraform.tfvars, -var lub TF_VAR_environment.
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

# Region AWS dla wszystkich zasobów; eu-central-1 = Frankfurt (blisko Polski, dane w UE).
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
