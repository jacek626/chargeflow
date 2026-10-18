# Wspólny prefiks nazw zasobów (np. chargeflow-dev).
variable "name_prefix" {
  type        = string
  description = "Prefix applied to all resource names"
}

# Repozytorium "org/repo" — trafia do warunku "sub" w trust policy (tylko to repo może przejąć rolę).
variable "github_repo" {
  type        = string
  description = "GitHub repository in org/repo format (e.g. acme/chargeflow)"
}
