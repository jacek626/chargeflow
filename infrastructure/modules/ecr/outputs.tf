# Outputy modułu ecr — URL-e repozytoriów (konto.dkr.ecr.region.amazonaws.com/nazwa), używane do docker push w CI i w environments/dev/outputs.tf.
# URL repo Charging Session Service.
output "charging_session_service_repo_url" {
  description = "ECR repository URL for charging-session-service"
  value       = aws_ecr_repository.repos["charging_session_service"].repository_url # dostęp do instancji for_each po kluczu mapy
}

# URL repo Notification Service.
output "notification_service_repo_url" {
  description = "ECR repository URL for notification-service"
  value       = aws_ecr_repository.repos["notification_service"].repository_url
}
