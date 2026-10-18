# Provider AWS skierowany na LocalStack (emulator API AWS w Dockerze, port 4566).
# UWAGA: brak bloku endpoints { ... } - endpoint pochodzi WYŁĄCZNIE z env AWS_ENDPOINT_URL.
# Bez niej (i z aktywnym AWS_PROFILE) apply stworzy zasoby chargeflow-local-* w PRAWDZIWYM AWS.
# Zawsze sprawdź env | grep AWS_ przed apply.
provider "aws" {
  region = var.aws_region # LocalStack przyjmie dowolny region; trzymamy ten sam co dev

  # Credentials and endpoint override are supplied via environment variables:
  #   AWS_ACCESS_KEY_ID     = test
  #   AWS_SECRET_ACCESS_KEY = test
  #   AWS_ENDPOINT_URL      = http://localhost:4566
  #
  # These are LocalStack dummy values — NOT real AWS credentials.

  # Wyłączenie kontroli, które mają sens tylko dla prawdziwego AWS:
  skip_credentials_validation = true # nie sprawdzaj kluczy test/test przez STS
  skip_metadata_api_check     = true # nie pytaj EC2 metadata (169.254.169.254) o poświadczenia
  skip_requesting_account_id  = true # nie pobieraj ID konta przez STS/IAM
  s3_use_path_style           = true # URL-e S3 w stylu localhost:4566/bucket zamiast bucket.localhost

  # default_tags: KAŻDY zasób tworzony tym providerem dostaje tagi Project/Environment/ManagedBy
  # (filtrowanie kosztów w Cost Explorer, odróżnienie od zasobów klikniętych ręcznie).
  default_tags {
    tags = local.common_tags # mapa tagów zdefiniowana w locals poniżej
  }
}

# locals = wartości wyliczane w module (jak private final w Javie); odwołanie: local.<nazwa>
locals {
  name_prefix = "${var.project_name}-${var.environment}" # "chargeflow-local" - wspólny prefiks nazw zasobów

  # Wspólne tagi; Environment pochodzi ze zmiennej, więc różni się per środowisko
  common_tags = {
    Project     = "ChargeFlow"    # nazwa projektu - filtr w billingu/Cost Explorer
    Environment = var.environment # local/dev/prod - pozwala liczyć koszty per środowisko
    ManagedBy   = "Terraform"     # sygnał: nie edytuj ręcznie w konsoli, zmiany tylko przez Terraform
  }
}
