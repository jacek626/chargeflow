# Zmienne wejściowe modułu network. Dev przekazuje tylko name_prefix i environment, reszta to wartości domyślne.
# Prefiks nazw (np. "chargeflow-dev") — rozróżnia zasoby różnych środowisk w jednym koncie AWS.
variable "name_prefix" {
  type        = string
  description = "Prefix applied to all resource names"
}

# Nazwa środowiska (np. dev).
# UWAGA: zmienna zadeklarowana, ale nieużywana w modules/network/main.tf (pozostałość / przygotowanie na przyszłość).
variable "environment" {
  type        = string
  description = "Deployment environment"
}

# Zakres adresów całej VPC; /16 daje 65 536 adresów — dużo zapasu na kolejne subnety.
variable "vpc_cidr" {
  type        = string
  description = "CIDR block for the VPC"
  default     = "10.0.0.0/16"
}

# Zakresy subnetów publicznych: 10.0.1.x i 10.0.2.x (po 256 adresów, /24). Liczba elementów = liczba subnetów.
variable "public_subnet_cidrs" {
  type        = list(string)
  description = "CIDR blocks for public subnets (one per AZ)"
  default     = ["10.0.1.0/24", "10.0.2.0/24"]
}

# Zakresy subnetów prywatnych: 10.0.10.x i 10.0.20.x. Nie mogą nachodzić na publiczne.
variable "private_subnet_cidrs" {
  type        = list(string)
  description = "CIDR blocks for private subnets (one per AZ)"
  default     = ["10.0.10.0/24", "10.0.20.0/24"]
}

# Strefy dostępności; indeks i-tego subnetu = indeks AZ, więc lista musi mieć co najmniej tyle elementów co listy CIDR.
# UWAGA: AZ wpisane na sztywno pod eu-central-1 — zmiana regionu bez zmiany tej listy da błąd przy apply.
variable "availability_zones" {
  type        = list(string)
  description = "Availability zones to deploy subnets into"
  default     = ["eu-central-1a", "eu-central-1b"]
}
