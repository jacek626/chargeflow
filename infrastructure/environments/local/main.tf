# Root module środowiska local: tylko zasoby emulowane przez LocalStack (port 4566).
# Brak sieci/ECS/RDS - Postgres działa w Dockerze, a serwisy uruchamiasz na hoście.
# Brak backend.tf => stan w lokalnym pliku terraform.tfstate w tym katalogu (gitignorowany).
# UWAGA: brak modułu cognito - lokalnie JWT wystawia profil Spring local (POST /dev/token).
#
# SNS (zdarzenia domenowe) + SQS z DLQ dla Notification Service - ten sam moduł co w dev.
module "messaging" {
  source = "../../modules/messaging" # ten sam kod modułu co w dev - różnią się tylko wartości

  name_prefix = local.name_prefix # "chargeflow-local" - nazwy zasobów w LocalStacku
  environment = var.environment   # "local" - do tagów i nazw
}

# Tabela DynamoDB na powiadomienia (w LocalStacku).
module "dynamodb" {
  source = "../../modules/dynamodb" # kod modułu DynamoDB

  name_prefix = local.name_prefix # nazwa tabeli np. chargeflow-local-notifications
  environment = var.environment   # środowisko do tagów
}

# Tożsamość nadawcy SES - LocalStack nie wysyła prawdziwych e-maili, tylko je zapamiętuje.
module "ses" {
  source = "../../modules/ses" # kod modułu SES

  from_email  = "noreply@chargeflow.pl" # adres na sztywno - local nie ma zmiennej ses_from_email
  environment = var.environment         # środowisko do tagów
}

# Grupy logów CloudWatch (emulowane).
module "monitoring" {
  source = "../../modules/monitoring" # kod modułu monitoringu

  name_prefix        = local.name_prefix # prefiks nazw grup logów
  environment        = var.environment   # środowisko do tagów
  log_retention_days = 7                 # krótka retencja - lokalnie logi nie są potrzebne długo
}
