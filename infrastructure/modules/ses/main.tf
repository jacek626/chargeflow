# SES email identity — zweryfikowany adres nadawcy e-maili Notification Service.
# Po apply AWS wysyła na ten adres link weryfikacyjny; dopóki ktoś go nie kliknie, SES odrzuci wysyłkę.
# W sandboxie SES (stan startowy konta) można też wysyłać tylko do zweryfikowanych odbiorców.
resource "aws_sesv2_email_identity" "sender" {
  email_identity = var.from_email # pojedynczy adres e-mail (zamiast domeny, która wymagałaby rekordów DNS/DKIM)

  tags = {
    Environment = var.environment
    Service     = "notification-service"
  }
}
