# ── GitHub Actions deployment ────────────────────────────────────────────────

# output = wartość wypisywana po apply (terraform output <nazwa>); czytają ją ludzie, CI i skrypty.
# ARN roli OIDC - wklejasz go raz jako sekret AWS_DEPLOY_ROLE_ARN w GitHub.
output "github_deploy_role_arn" {
  description = "IAM role ARN for GitHub Actions OIDC — set as AWS_DEPLOY_ROLE_ARN in GitHub repository secrets"
  value       = module.iam_github.deploy_role_arn
}

# ── Networking ───────────────────────────────────────────────────────────────

# ID VPC - przydatne przy diagnostyce i ręcznych poleceniach AWS CLI.
output "vpc_id" {
  description = "VPC ID"
  value       = module.network.vpc_id
}

# Publiczny adres DNS ALB = bazowy URL REST API (deploy.yml wypisuje go w podsumowaniu).
output "alb_dns_name" {
  description = "ALB DNS name (API base URL)"
  value       = module.alb.alb_dns_name
}

# ── Container registries ──────────────────────────────────────────────────────

# Adres repo ECR, do którego CI robi docker push obrazu charging-session-service.
output "charging_session_service_repo_url" {
  description = "ECR repo URL for charging-session-service"
  value       = module.ecr.charging_session_service_repo_url
}

# Adres repo ECR dla obrazu notification-service.
output "notification_service_repo_url" {
  description = "ECR repo URL for notification-service"
  value       = module.ecr.notification_service_repo_url
}

# ── Compute ───────────────────────────────────────────────────────────────────

# Nazwa klastra ECS - potrzebna np. do aws ecs update-service / list-tasks.
output "ecs_cluster_name" {
  description = "ECS cluster name"
  value       = module.ecs.cluster_name
}

# ── Frontend ──────────────────────────────────────────────────────────────────

# Nazwa bucketu frontendu - deploy.yml robi do niego aws s3 sync z buildem Angulara.
output "frontend_bucket_name" {
  description = "S3 bucket name for the Angular SPA"
  value       = module.frontend.s3_bucket_name
}

# Domena CloudFront - publiczny adres aplikacji Angular (HTTPS).
output "frontend_cloudfront_domain" {
  description = "CloudFront distribution domain (public URL)"
  value       = module.frontend.cloudfront_domain_name
}

# ID dystrybucji CloudFront - deploy.yml używa go do invalidacji cache po wgraniu nowego buildu.
output "frontend_cloudfront_distribution_id" {
  description = "CloudFront distribution ID (for cache invalidation)"
  value       = module.frontend.cloudfront_distribution_id
}

# ── Messaging ─────────────────────────────────────────────────────────────────

# ARN topicu SNS ze zdarzeniami domenowymi (konfiguracja Charging Session Service).
output "sns_topic_arn" {
  description = "Domain events SNS topic ARN"
  value       = module.messaging.sns_topic_arn
}

# Nazwa topicu SNS (wygodniejsza niż ARN przy ręcznym podglądzie w konsoli).
output "sns_topic_name" {
  description = "Domain events SNS topic name"
  value       = module.messaging.sns_topic_name
}

# URL kolejki SQS - SDK używa URL (nie ARN) do odbierania wiadomości.
output "sqs_queue_url" {
  description = "Notifications SQS queue URL"
  value       = module.messaging.sqs_queue_url
}

# ARN kolejki SQS - używany w politykach IAM i subskrypcji SNS.
output "sqs_queue_arn" {
  description = "Notifications SQS queue ARN"
  value       = module.messaging.sqs_queue_arn
}

# URL kolejki DLQ - tu trafiają wiadomości, których nie udało się przetworzyć po kilku próbach.
output "sqs_dlq_url" {
  description = "Notifications DLQ URL"
  value       = module.messaging.sqs_dlq_url
}

# ARN kolejki DLQ - np. do alarmów CloudWatch.
output "sqs_dlq_arn" {
  description = "Notifications DLQ ARN"
  value       = module.messaging.sqs_dlq_arn
}

# ── Auth ──────────────────────────────────────────────────────────────────────

# ID User Poola Cognito - konfiguracja logowania we frontendzie.
output "cognito_user_pool_id" {
  description = "Cognito User Pool ID"
  value       = module.cognito.user_pool_id
}

# Client ID aplikacji Angular w Cognito (publiczny, bez sekretu - SPA nie przechowa sekretu).
output "cognito_client_id" {
  description = "Angular app client ID"
  value       = module.cognito.client_id
}

# URL z kluczami publicznymi (JWKS) - Spring weryfikuje nim podpis tokenów JWT.
output "cognito_jwks_uri" {
  description = "JWKS URI for Spring JWT validation"
  value       = module.cognito.jwks_uri
}

# ── Database ──────────────────────────────────────────────────────────────────

# Adres bazy RDS w formacie host:port.
output "rds_endpoint" {
  description = "RDS endpoint (host:port)"
  value       = module.rds.endpoint
}

# ARN sekretu z hasłem do bazy (samo hasło pobierzesz przez aws secretsmanager get-secret-value).
output "rds_db_password_secret_arn" {
  description = "Secrets Manager ARN for the RDS master password"
  value       = module.rds.db_password_secret_arn
  sensitive   = true # nie wypisuje wartości w konsoli/logach CI - ale w pliku stanu nadal jest jawnym tekstem!
}

# ── Notification storage ──────────────────────────────────────────────────────

# Nazwa tabeli DynamoDB z powiadomieniami.
output "dynamodb_notifications_table_name" {
  description = "DynamoDB notifications table name"
  value       = module.dynamodb.notifications_table_name
}
