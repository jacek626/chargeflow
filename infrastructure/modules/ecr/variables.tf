# Zmienne wejściowe modułu ecr.
# Prefiks nazw repozytoriów (np. "chargeflow-dev").
variable "name_prefix" {
  type        = string
  description = "Prefix applied to all resource names"
}

# Nazwa środowiska.
# UWAGA: zmienna zadeklarowana, ale nieużywana w modules/ecr/main.tf.
variable "environment" {
  type        = string
  description = "Deployment environment"
}

# Ile najnowszych obrazów trzymać w każdym repo (lifecycle policy); więcej = więcej opcji rollbacku, ale wyższy koszt storage.
variable "image_count_to_keep" {
  type        = number
  description = "Number of most-recent images to retain per repository"
  default     = 10
}
