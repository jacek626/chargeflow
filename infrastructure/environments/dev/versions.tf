# Blok terraform: wymagania co do wersji samego Terraform CLI i providerów.
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
    # Provider random - generuje losowe wartości (tu hasło do RDS) zapisywane w stanie
    random = {
      source  = "hashicorp/random" # oficjalny provider HashiCorp
      version = "~> 3.6"           # ~> 3.6 = dowolne 3.x od 3.6 wzwyż, bez 4.0
    }
  }
}
