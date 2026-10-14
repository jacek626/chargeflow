output "user_pool_id" {
  description = "Cognito User Pool ID"
  value       = aws_cognito_user_pool.main.id
}

output "user_pool_arn" {
  description = "Cognito User Pool ARN"
  value       = aws_cognito_user_pool.main.arn
}

output "client_id" {
  description = "Angular app client ID"
  value       = aws_cognito_user_pool_client.angular.id
}

output "domain" {
  description = "Cognito hosted UI domain prefix"
  value       = aws_cognito_user_pool_domain.main.domain
}

output "jwks_uri" {
  description = "JWKS URI for Spring OAuth2 Resource Server JWT validation"
  value       = "https://cognito-idp.${var.environment == "local" ? "us-east-1" : "eu-central-1"}.amazonaws.com/${aws_cognito_user_pool.main.id}/.well-known/jwks.json"
}

output "issuer_uri" {
  description = "Token issuer URI"
  value       = "https://cognito-idp.${var.environment == "local" ? "us-east-1" : "eu-central-1"}.amazonaws.com/${aws_cognito_user_pool.main.id}"
}
