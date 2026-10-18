# Nazwy log groups z tego modułu.
# UWAGA: ECS z nich nie korzysta (pisze do grup "/ecs" z modułu ecs), więc te wyjścia nic nie konfigurują.
output "charging_session_log_group_name" {
  description = "CloudWatch log group name for charging-session-service"
  value       = aws_cloudwatch_log_group.charging_session_service.name
}

# Nazwa log group Notification Service (również nieużywanej przez ECS).
output "notification_log_group_name" {
  description = "CloudWatch log group name for notification-service"
  value       = aws_cloudwatch_log_group.notification_service.name
}
