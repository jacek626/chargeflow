# Adres e-mail nadawcy powiadomień — musisz mieć dostęp do tej skrzynki, by kliknąć link weryfikacyjny.
variable "from_email" {
  description = "Email address to verify in SES (used as notification sender)"
  type        = string
}

# Nazwa środowiska — trafia do tagu Environment.
variable "environment" {
  description = "Deployment environment"
  type        = string
}
