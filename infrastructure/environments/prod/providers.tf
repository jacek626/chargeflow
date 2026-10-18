# Konfiguracja providera AWS dla prod: region + tagi doklejane do każdego zasobu.
# UWAGA: prod jest szkieletem - provider jest skonfigurowany, ale nie ma żadnych zasobów (brak main.tf).
provider "aws" {
  region = var.aws_region # region z variables.tf (domyślnie eu-central-1)

  # Credentials are obtained from standard AWS credential mechanisms:
  #   - Environment variables: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
  #   - IAM task role (ECS Fargate)
  #   - GitHub Actions OIDC (CI/CD)
  #
  # Do NOT hardcode credentials here.

  # default_tags: KAŻDY zasób tworzony tym providerem dostaje tagi Project/Environment/ManagedBy
  # (filtrowanie kosztów w Cost Explorer, odróżnienie od zasobów klikniętych ręcznie).
  default_tags {
    tags = local.common_tags # mapa tagów zdefiniowana w locals poniżej
  }
}

# locals = wartości wyliczane w module (jak private final w Javie); odwołanie: local.<nazwa>
# name_prefix nie jest jeszcze nigdzie używany (brak modułów w prod).
locals {
  name_prefix = "${var.project_name}-${var.environment}" # "chargeflow-prod" - wspólny prefiks nazw zasobów

  # Wspólne tagi; Environment pochodzi ze zmiennej, więc różni się per środowisko
  common_tags = {
    Project     = "ChargeFlow"    # nazwa projektu - filtr w billingu/Cost Explorer
    Environment = var.environment # local/dev/prod - pozwala liczyć koszty per środowisko
    ManagedBy   = "Terraform"     # sygnał: nie edytuj ręcznie w konsoli, zmiany tylko przez Terraform
  }
}
