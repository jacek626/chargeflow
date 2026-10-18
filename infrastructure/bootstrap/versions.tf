# Blok terraform: wymagania co do wersji samego Terraform CLI i providerów.
# UWAGA: brak bloku backend - stan SAMEGO bootstrapu leży lokalnie w terraform.tfstate
# (problem jajka i kury: bucket na stan nie może trzymać stanu, który go tworzy). Nie zgub go.
terraform {
  required_version = "~> 1.11" # ~> 1.11 = dowolne 1.x od 1.11 wzwyż (1.12, 1.13...), ale nigdy 2.0

  # Providery = pluginy tłumaczące HCL na wywołania API (tu AWS); pobiera je terraform init,
  # a dokładne wersje zapisuje w .terraform.lock.hcl (commitowany do repo).
  required_providers {
    # Lokalna nazwa "aws" -> adres providera w Terraform Registry + dozwolone wersje
    aws = {
      source  = "hashicorp/aws" # registry.terraform.io/hashicorp/aws (oficjalny provider HashiCorp)
      version = "~> 6.64.0"     # ~> 6.64.0 = tylko poprawki 6.64.x (nie 6.65) - przewidywalne buildy
    }
  }
}

# Konfiguracja providera AWS: region docelowy. Poświadczenia z env lub ~/.aws
# (bootstrap uruchamiasz raz, ręcznie, z konta z uprawnieniami do S3 i DynamoDB).
provider "aws" {
  region = var.aws_region # region z variables.tf (domyślnie eu-central-1)
}
