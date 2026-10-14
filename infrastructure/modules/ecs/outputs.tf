output "cluster_name" {
  description = "ECS cluster name"
  value       = aws_ecs_cluster.main.name
}

output "cluster_arn" {
  description = "ECS cluster ARN"
  value       = aws_ecs_cluster.main.arn
}

output "charging_session_service_name" {
  description = "ECS service name for charging-session-service"
  value       = aws_ecs_service.charging_session.name
}

output "notification_service_name" {
  description = "ECS service name for notification-service"
  value       = aws_ecs_service.notification.name
}
