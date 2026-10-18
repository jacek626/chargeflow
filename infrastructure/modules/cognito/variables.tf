# Wspólny prefiks nazw zasobów (np. chargeflow-dev), używany też jako prefiks domeny Hosted UI.
variable "name_prefix" {
  type        = string
  description = "Prefix applied to all resource names"
}

# Nazwa środowiska (local/dev) — w outputs wybiera region dla issuer/JWKS URI.
variable "environment" {
  type        = string
  description = "Deployment environment"
}

# Adresy, na które Cognito może odesłać użytkownika z kodem autoryzacyjnym po zalogowaniu.
# UWAGA: domyślny port 4200, a frontend startuje na 4201 (frontend/package.json: ng serve --port 4201)
# — Cognito odrzuci redirect_uri, jeśli środowisko nie nadpisze tej wartości.
variable "callback_urls" {
  type        = list(string)
  description = "Allowed OAuth2 redirect URIs for the Angular app"
  default     = ["http://localhost:4200/auth/callback"]
}

# Adresy, na które Cognito może przekierować po wylogowaniu.
# UWAGA: ten sam problem z portem 4200 vs 4201.
variable "logout_urls" {
  type        = list(string)
  description = "Allowed logout redirect URIs for the Angular app"
  default     = ["http://localhost:4200"]
}
