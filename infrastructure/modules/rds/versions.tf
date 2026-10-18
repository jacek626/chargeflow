# Wymagane providery modułu — "random" jest potrzebny do wygenerowania hasła bazy.
terraform {
  required_providers {
    random = {
      source  = "hashicorp/random" # oficjalny provider HashiCorp z Terraform Registry
      version = "~> 3.6"           # dowolna wersja 3.x od 3.6 wzwyż (bez 4.0) — chroni przed breaking changes
    }
  }
}
