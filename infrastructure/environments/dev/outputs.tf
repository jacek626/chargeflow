# ── GitHub Actions deployment ────────────────────────────────────────────────

output "github_deploy_role_arn" {
  description = "IAM role ARN for GitHub Actions OIDC — set as AWS_DEPLOY_ROLE_ARN in GitHub repository secrets"
  value       = module.iam_github.deploy_role_arn
}

# ── Networking ───────────────────────────────────────────────────────────────

output "vpc_id" {
  description = "VPC ID"
  value       = module.network.vpc_id
}

output "alb_dns_name" {
  description = "ALB DNS name (API base URL)"
  value       = module.alb.alb_dns_name
}

# ── Container registries ──────────────────────────────────────────────────────

output "charging_session_service_repo_url" {
  description = "ECR repo URL for charging-session-service"
  value       = module.ecr.charging_session_service_repo_url
}

output "notification_service_repo_url" {
  description = "ECR repo URL for notification-service"
  value       = module.ecr.notification_service_repo_url
}

# ── Compute ───────────────────────────────────────────────────────────────────

output "ecs_cluster_name" {
  description = "ECS cluster name"
  value       = module.ecs.cluster_name
}

# ── Frontend ──────────────────────────────────────────────────────────────────

output "frontend_bucket_name" {
  description = "S3 bucket name for the Angular SPA"
  value       = module.frontend.s3_bucket_name
}

output "frontend_cloudfront_domain" {
  description = "CloudFront distribution domain (public URL)"
  value       = module.frontend.cloudfront_domain_name
}

output "frontend_cloudfront_distribution_id" {
  description = "CloudFront distribution ID (for cache invalidation)"
  value       = module.frontend.cloudfront_distribution_id
}

# ── Messaging ─────────────────────────────────────────────────────────────────

output "sns_topic_arn" {
  description = "Domain events SNS topic ARN"
  value       = module.messaging.sns_topic_arn
}

output "sns_topic_name" {
  description = "Domain events SNS topic name"
  value       = module.messaging.sns_topic_name
}

output "sqs_queue_url" {
  description = "Notifications SQS queue URL"
  value       = module.messaging.sqs_queue_url
}

output "sqs_queue_arn" {
  description = "Notifications SQS queue ARN"
  value       = module.messaging.sqs_queue_arn
}

output "sqs_dlq_url" {
  description = "Notifications DLQ URL"
  value       = module.messaging.sqs_dlq_url
}

output "sqs_dlq_arn" {
  description = "Notifications DLQ ARN"
  value       = module.messaging.sqs_dlq_arn
}

# ── Auth ──────────────────────────────────────────────────────────────────────

output "cognito_user_pool_id" {
  description = "Cognito User Pool ID"
  value       = module.cognito.user_pool_id
}

output "cognito_client_id" {
  description = "Angular app client ID"
  value       = module.cognito.client_id
}

output "cognito_jwks_uri" {
  description = "JWKS URI for Spring JWT validation"
  value       = module.cognito.jwks_uri
}

# ── Database ──────────────────────────────────────────────────────────────────

output "rds_endpoint" {
  description = "RDS endpoint (host:port)"
  value       = module.rds.endpoint
}

output "rds_db_password_secret_arn" {
  description = "Secrets Manager ARN for the RDS master password"
  value       = module.rds.db_password_secret_arn
  sensitive   = true
}

# ── Notification storage ──────────────────────────────────────────────────────

output "dynamodb_notifications_table_name" {
  description = "DynamoDB notifications table name"
  value       = module.dynamodb.notifications_table_name
}
