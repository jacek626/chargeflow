output "charging_session_log_group_name" {
  description = "CloudWatch log group name for charging-session-service"
  value       = aws_cloudwatch_log_group.charging_session_service.name
}

output "notification_log_group_name" {
  description = "CloudWatch log group name for notification-service"
  value       = aws_cloudwatch_log_group.notification_service.name
}
