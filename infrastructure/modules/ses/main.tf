resource "aws_sesv2_email_identity" "sender" {
  email_identity = var.from_email

  tags = {
    Environment = var.environment
    Service     = "notification-service"
  }
}
