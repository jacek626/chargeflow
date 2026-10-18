# Prefiks nazw zasobów (tu niewykorzystywany — nazwy log groups budowane są z environment).
variable "name_prefix" {
  description = "Resource name prefix"
  type        = string
}

# Nazwa środowiska — część ścieżki log group i tag Environment.
variable "environment" {
  description = "Deployment environment"
  type        = string
}

# Retencja logów w dniach (CloudWatch akceptuje tylko wybrane wartości: 1, 3, 5, 7, 14, 30, 60, 90...).
# UWAGA: nie wpływa na faktyczne logi ECS — tam retencja jest ustawiona na sztywno w module ecs.
variable "log_retention_days" {
  description = "CloudWatch log retention in days"
  type        = number
  default     = 30
}
