output "charging_session_service_repo_url" {
  description = "ECR repository URL for charging-session-service"
  value       = aws_ecr_repository.repos["charging_session_service"].repository_url
}

output "notification_service_repo_url" {
  description = "ECR repository URL for notification-service"
  value       = aws_ecr_repository.repos["notification_service"].repository_url
}
