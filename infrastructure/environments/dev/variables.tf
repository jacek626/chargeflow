# Repozytorium GitHub (org/repo) - trafia do trust policy roli OIDC w module iam_github.
# Brak default: CI przekazuje je przez -var github_repo=${{ github.repository }}.
variable "github_repo" {
  type        = string
  description = "GitHub repository in org/repo format (e.g. acme/chargeflow)"
}

# Nazwa środowiska: buduje prefiks nazw (chargeflow-dev) i tag Environment.
# UWAGA: brak default, *.tfvars są w .gitignore, a deploy.yml nie przekazuje
# -var environment ani TF_VAR_environment - nieinteraktywny apply w CI skończy się błędem.
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

# Adresy, na które Cognito może przekierować po zalogowaniu (OAuth2 Authorization Code flow).
# Muszą dokładnie pasować do redirect_uri wysyłanego przez Angulara.
variable "cognito_callback_urls" {
  type        = list(string)
  description = "OAuth2 redirect URIs for the Angular app"
  default     = ["https://app.chargeflow.dev/auth/callback"]
}

# Adresy, na które Cognito może przekierować po wylogowaniu.
variable "cognito_logout_urls" {
  type        = list(string)
  description = "Logout redirect URIs for the Angular app"
  default     = ["https://app.chargeflow.dev"]
}

# Adres nadawcy e-maili. Nowe konto SES jest w "sandboxie": wysyłka tylko
# do zweryfikowanych adresów, dopóki nie poprosisz AWS o production access.
variable "ses_from_email" {
  type        = string
  description = "Sender email address verified in SES"
  default     = "noreply@chargeflow.pl"
}

# Retencja logów CloudWatch; 30 dni to kompromis koszt/diagnostyka dla dev.
variable "log_retention_days" {
  type        = number
  description = "CloudWatch log retention in days"
  default     = 30
}

# ── Container images ──────────────────────────────────────────────────────────

# URI obrazu charging-session-service. Placeholder pozwala zrobić pierwszy apply
# przed zbudowaniem obrazów (taski nie wystartują, dopóki CI nie poda prawdziwego URI).
variable "charging_session_image" {
  type        = string
  description = "Full ECR image URI for charging-session-service (e.g. 123456.dkr.ecr.eu-central-1.amazonaws.com/chargeflow-dev-charging-session-service:latest)"
  default     = "placeholder/charging-session-service:latest"
}

# URI obrazu notification-service - jak wyżej, CI nadpisuje go przez -var.
variable "notification_image" {
  type        = string
  description = "Full ECR image URI for notification-service"
  default     = "placeholder/notification-service:latest"
}

# ── ECS sizing ────────────────────────────────────────────────────────────────

# CPU taska Fargate: 256 = 0.25 vCPU. 256 CPU + 512 MiB to najmniejsza (najtańsza) kombinacja.
variable "ecs_task_cpu" {
  type        = number
  description = "vCPU units for each ECS task (256 = 0.25 vCPU)"
  default     = 256
}

# Pamięć taska w MiB; dla 256 CPU dozwolone są 512, 1024 lub 2048.
variable "ecs_task_memory" {
  type        = number
  description = "Memory for each ECS task in MiB"
  default     = 512
}

# Liczba tasków na serwis; 1 = brak redundancji (oszczędność w dev), prod raczej >= 2 w różnych AZ.
variable "ecs_desired_count" {
  type        = number
  description = "Desired number of running tasks per service"
  default     = 1
}

# ── TLS / Domain ─────────────────────────────────────────────────────────────

# Certyfikat ACM dla listenera HTTPS na ALB (ten sam region co ALB).
# Pusty = tylko HTTP:80 - akceptowalne do nauki, nie na produkcję.
variable "acm_certificate_arn" {
  type        = string
  description = "ACM certificate ARN for the ALB HTTPS listener (empty string = HTTP only)"
  default     = ""
}

# Certyfikat dla CloudFront - usługa globalna, akceptuje certyfikaty ACM tylko z us-east-1.
variable "frontend_acm_certificate_arn" {
  type        = string
  description = "ACM certificate ARN for CloudFront (must be in us-east-1; empty string = CloudFront default cert)"
  default     = ""
}

# Własne domeny frontendu; wymagają certyfikatu obejmującego te domeny i rekordu DNS (alias/CNAME).
variable "frontend_domain_aliases" {
  type        = list(string)
  description = "Custom domain aliases for CloudFront (e.g. [\"app.chargeflow.dev\"])"
  default     = []
}
