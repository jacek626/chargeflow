# Adres nadawcy — przekazywany do Notification Service jako pole "From" wysyłanych e-maili.
output "from_email" {
  description = "Verified SES sender email address"
  value       = aws_sesv2_email_identity.sender.email_identity
}

# ARN identity — do zawężenia uprawnień IAM ses:SendEmail do tego nadawcy.
output "email_identity_arn" {
  description = "SES email identity ARN"
  value       = aws_sesv2_email_identity.sender.arn
}
