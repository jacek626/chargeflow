# S3 remote state backend.
# Pass bucket/region/dynamodb_table at init time:
#
#   terraform init \
#     -backend-config="bucket=<your-tfstate-bucket>" \
#     -backend-config="region=eu-central-1" \
#     -backend-config="dynamodb_table=chargeflow-tfstate-lock"
#
# The bucket is created by infrastructure/bootstrap.
# Blok terraform z konfiguracją backendu - GDZIE Terraform trzyma stan środowiska dev.
# UWAGA: dynamodb_table jest deprecated od Terraform 1.11; nowszy zamiennik to use_lockfile = true.
terraform {
  # Backend S3: stan w buckecie z bootstrapu, współdzielony przez ludzi i CI (deploy.yml).
  # Konfiguracja "partial": bucket/region/dynamodb_table dochodzą przez -backend-config,
  # bo w bloku backend nie wolno używać zmiennych (var.* tu nie działa).
  backend "s3" {
    key = "dev/terraform.tfstate" # ścieżka obiektu w buckecie; prod musi mieć inny key (np. prod/terraform.tfstate)
  }
}
