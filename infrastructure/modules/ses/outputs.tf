output "from_email" {
  description = "Verified SES sender email address"
  value       = aws_sesv2_email_identity.sender.email_identity
}

output "email_identity_arn" {
  description = "SES email identity ARN"
  value       = aws_sesv2_email_identity.sender.arn
}
