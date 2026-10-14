resource "aws_cloudwatch_log_group" "charging_session_service" {
  name              = "/chargeflow/${var.environment}/charging-session-service"
  retention_in_days = var.log_retention_days

  tags = {
    Environment = var.environment
    Service     = "charging-session-service"
  }
}

resource "aws_cloudwatch_log_group" "notification_service" {
  name              = "/chargeflow/${var.environment}/notification-service"
  retention_in_days = var.log_retention_days

  tags = {
    Environment = var.environment
    Service     = "notification-service"
  }
}
