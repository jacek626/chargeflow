# Wejścia modułu RDS — wartości podawane przez wywołujące środowisko (np. environments/dev).
# Prefiks nazw zasobów, np. "chargeflow-dev" — rozróżnia środowiska na jednym koncie.
variable "name_prefix" {
  type        = string
  description = "Prefix applied to all resource names"
}

# Nazwa środowiska (dev/prod...).
variable "environment" {
  type        = string
  description = "Deployment environment"
}

# UWAGA: zmienna nieużywana w module — VPC wynika już z subnet_ids i security_group_id.
variable "vpc_id" {
  type        = string
  description = "VPC ID"
}

# Prywatne podsieci dla DB subnet group (co najmniej dwie, w różnych AZ).
variable "subnet_ids" {
  type        = list(string)
  description = "Private subnet IDs for the DB subnet group"
}

# Security group bazy — reguły dostępu sieciowego definiowane w module sieci.
variable "security_group_id" {
  type        = string
  description = "Security group ID for the RDS instance"
}

# Nazwa bazy danych tworzonej w instancji.
variable "db_name" {
  type        = string
  description = "Database name"
  default     = "chargeflow"
}

# Login użytkownika master.
variable "db_username" {
  type        = string
  description = "Database master username"
  default     = "chargeflow"
}

# Klasa instancji: "t4g" = burstable na procesorze Graviton (ARM, taniej), "micro" = 2 vCPU/1 GiB — wystarczy na dev.
variable "instance_class" {
  type        = string
  description = "RDS instance class"
  default     = "db.t4g.micro"
}

# 20 GiB to minimum dla gp3 w RDS PostgreSQL.
variable "allocated_storage_gb" {
  type        = number
  description = "Allocated storage in GiB"
  default     = 20
}

# Główna wersja PostgreSQL (używana też w nazwie i family parameter group).
# UWAGA: "16" tutaj vs postgres:18.6 w docker-compose — różne wersje lokalnie i w AWS.
variable "postgres_version" {
  type        = string
  description = "PostgreSQL engine version"
  default     = "16"
}

# Ochrona przed usunięciem instancji; false w dev, by terraform destroy działał (kosztem utraty danych).
variable "deletion_protection" {
  type        = bool
  description = "Enable deletion protection"
  default     = false
}
