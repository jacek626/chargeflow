# User Pool — email as username, email verification required
# User Pool = katalog użytkowników ChargeFlow + serwer autoryzacji OAuth2/OIDC.
# Wystawia JWT, które Charging Session Service weryfikuje jako OAuth2 Resource Server.
resource "aws_cognito_user_pool" "main" {
  name = "${var.name_prefix}-users" # np. chargeflow-dev-users

  username_attributes      = ["email"] # użytkownik loguje się emailem zamiast osobnego loginu
  auto_verified_attributes = ["email"] # Cognito wysyła kod weryfikacyjny na email przy rejestracji

  # Polityka haseł dla użytkowników zakładających konto przez Hosted UI.
  password_policy {
    minimum_length    = 8     # minimalna długość hasła
    require_lowercase = true  # wymagana mała litera
    require_numbers   = true  # wymagana cyfra
    require_symbols   = false # znak specjalny niewymagany (kompromis wygoda/bezpieczeństwo)
    require_uppercase = true  # wymagana wielka litera
  }

  # Definicja atrybutu "email" w schemacie puli (standardowy atrybut OIDC).
  # Uwaga praktyczna: schematu atrybutów nie da się zmienić po utworzeniu puli bez jej odtworzenia.
  schema {
    name                = "email"
    attribute_data_type = "String"
    mutable             = true # użytkownik może później zmienić email
    required            = true # email obowiązkowy przy rejestracji (jest też loginem)
    string_attribute_constraints {
      min_length = 3   # najkrótszy sensowny email, np. a@b
      max_length = 254 # maksymalna długość adresu email wg RFC
    }
  }
}

# Groups — used in cognito:groups JWT claim, mapped to ROLE_CUSTOMER / ROLE_OPERATOR in Spring
# Grupa klientów ładujących pojazdy (rola ROLE_CUSTOMER po stronie Springa).
resource "aws_cognito_user_group" "customer" {
  name         = "CUSTOMER" # nazwa trafia dosłownie do claimu cognito:groups w tokenie
  user_pool_id = aws_cognito_user_pool.main.id
  description  = "Regular charging customers"
}

# Grupa operatorów stacji ładowania (rola ROLE_OPERATOR po stronie Springa).
resource "aws_cognito_user_group" "operator" {
  name         = "OPERATOR" # nazwa trafia dosłownie do claimu cognito:groups w tokenie
  user_pool_id = aws_cognito_user_pool.main.id
  description  = "Charging station operators"
}

# App Client — Angular PKCE (no client secret, public client)
# App Client = "aplikacja" uprawniona do logowania w puli (client_id w OAuth2) — tu frontend Angular.
resource "aws_cognito_user_pool_client" "angular" {
  name         = "${var.name_prefix}-angular"
  user_pool_id = aws_cognito_user_pool.main.id

  # Public client: SPA w przeglądarce nie utrzyma sekretu (bundle JS jest publiczny); rolę sekretu pełni PKCE.
  generate_secret = false

  allowed_oauth_flows                  = ["code"]                       # Authorization Code (z PKCE); bez przestarzałego "implicit"
  allowed_oauth_flows_user_pool_client = true                           # włącza obsługę OAuth2 (Hosted UI) dla tego klienta
  allowed_oauth_scopes                 = ["openid", "email", "profile"] # openid = OIDC (ID token), email/profile = dane użytkownika
  supported_identity_providers         = ["COGNITO"]                    # logowanie tylko kontami z tej puli (bez Google/Facebook itp.)

  # Dozwolone adresy powrotu po logowaniu/wylogowaniu — Cognito odrzuci redirect_uri spoza listy.
  callback_urls = var.callback_urls
  logout_urls   = var.logout_urls

  # Przepływy logowania przez API (poza Hosted UI).
  # UWAGA: brak ALLOW_USER_PASSWORD_AUTH, a scripts/get-local-token.sh używa --auth-flow USER_PASSWORD_AUTH
  # — ten skrypt nie zadziała z tym klientem na prawdziwym Cognito.
  explicit_auth_flows = [
    "ALLOW_USER_SRP_AUTH",      # logowanie SRP — hasło nie jest przesyłane wprost
    "ALLOW_REFRESH_TOKEN_AUTH", # odświeżanie access/ID tokenów refresh tokenem
  ]

  # Brak ustawień token validity => domyślne wartości Cognito: access/ID token 1 h, refresh token 30 dni.
  # Przy błędnym logowaniu nie zdradza, czy dany email istnieje w puli (ochrona przed enumeracją kont).
  prevent_user_existence_errors = "ENABLED"
}

# Hosted UI domain — required for OAuth2 Authorization Code flow
# Tworzy domenę https://<prefix>.auth.<region>.amazoncognito.com z endpointami /oauth2/authorize i /oauth2/token.
resource "aws_cognito_user_pool_domain" "main" {
  domain       = var.name_prefix # prefiks domeny musi być globalnie unikalny w regionie
  user_pool_id = aws_cognito_user_pool.main.id
}
