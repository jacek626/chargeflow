# ID puli — potrzebne m.in. do budowy issuer/JWKS URI i konfiguracji frontendu.
output "user_pool_id" {
  description = "Cognito User Pool ID"
  value       = aws_cognito_user_pool.main.id
}

# ARN puli — przydatny do polityk IAM / integracji z innymi usługami AWS.
output "user_pool_arn" {
  description = "Cognito User Pool ARN"
  value       = aws_cognito_user_pool.main.arn
}

# client_id klienta Angular — wpisywany w konfigurację frontendu (parametr client_id w /oauth2/authorize).
output "client_id" {
  description = "Angular app client ID"
  value       = aws_cognito_user_pool_client.angular.id
}

# Prefiks domeny Hosted UI — frontend buduje z niego adres strony logowania.
output "domain" {
  description = "Cognito hosted UI domain prefix"
  value       = aws_cognito_user_pool_domain.main.domain
}

# Adres kluczy publicznych (JWKS), którymi Spring weryfikuje podpis JWT.
# UWAGA: region jest zahardkodowany (local -> us-east-1, reszta -> eu-central-1) zamiast brany
# z providera (np. data "aws_region"); przy innym regionie URI będzie błędny.
output "jwks_uri" {
  description = "JWKS URI for Spring OAuth2 Resource Server JWT validation"
  value       = "https://cognito-idp.${var.environment == "local" ? "us-east-1" : "eu-central-1"}.amazonaws.com/${aws_cognito_user_pool.main.id}/.well-known/jwks.json"
}

# Issuer (claim "iss" w tokenie) — Spring sprawdza, czy token pochodzi z tej puli.
# UWAGA: ten sam zahardkodowany region co w jwks_uri.
output "issuer_uri" {
  description = "Token issuer URI"
  value       = "https://cognito-idp.${var.environment == "local" ? "us-east-1" : "eu-central-1"}.amazonaws.com/${aws_cognito_user_pool.main.id}"
}
