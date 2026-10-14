# User Pool — email as username, email verification required
resource "aws_cognito_user_pool" "main" {
  name = "${var.name_prefix}-users"

  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  password_policy {
    minimum_length    = 8
    require_lowercase = true
    require_numbers   = true
    require_symbols   = false
    require_uppercase = true
  }

  schema {
    name                = "email"
    attribute_data_type = "String"
    mutable             = true
    required            = true
    string_attribute_constraints {
      min_length = 3
      max_length = 254
    }
  }
}

# Groups — used in cognito:groups JWT claim, mapped to ROLE_CUSTOMER / ROLE_OPERATOR in Spring
resource "aws_cognito_user_group" "customer" {
  name         = "CUSTOMER"
  user_pool_id = aws_cognito_user_pool.main.id
  description  = "Regular charging customers"
}

resource "aws_cognito_user_group" "operator" {
  name         = "OPERATOR"
  user_pool_id = aws_cognito_user_pool.main.id
  description  = "Charging station operators"
}

# App Client — Angular PKCE (no client secret, public client)
resource "aws_cognito_user_pool_client" "angular" {
  name         = "${var.name_prefix}-angular"
  user_pool_id = aws_cognito_user_pool.main.id

  generate_secret = false

  allowed_oauth_flows                  = ["code"]
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_scopes                 = ["openid", "email", "profile"]
  supported_identity_providers         = ["COGNITO"]

  callback_urls = var.callback_urls
  logout_urls   = var.logout_urls

  explicit_auth_flows = [
    "ALLOW_USER_SRP_AUTH",
    "ALLOW_REFRESH_TOKEN_AUTH",
  ]

  prevent_user_existence_errors = "ENABLED"
}

# Hosted UI domain — required for OAuth2 Authorization Code flow
resource "aws_cognito_user_pool_domain" "main" {
  domain       = var.name_prefix
  user_pool_id = aws_cognito_user_pool.main.id
}
