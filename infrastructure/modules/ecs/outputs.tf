# Outputs modułu ECS — wartości wystawiane do environments/dev (tam używany jest cluster_name);
# nazwy serwisów przydają się np. do ręcznego `aws ecs update-service --cluster ... --service ...`.

# Nazwa klastra ECS (np. chargeflow-dev-cluster).
output "cluster_name" {
  description = "ECS cluster name"
  value       = aws_ecs_cluster.main.name
}

# Pełny ARN klastra — przydatny w politykach IAM i odwołaniach między usługami.
output "cluster_arn" {
  description = "ECS cluster ARN"
  value       = aws_ecs_cluster.main.arn
}

# Nazwa serwisu charging-session-service — potrzebna np. do metryk ECS w CloudWatch.
output "charging_session_service_name" {
  description = "ECS service name for charging-session-service"
  value       = aws_ecs_service.charging_session.name
}

# Nazwa serwisu notification-service.
output "notification_service_name" {
  description = "ECS service name for notification-service"
  value       = aws_ecs_service.notification.name
}
