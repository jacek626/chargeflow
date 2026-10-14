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

output "dynamodb_notifications_table_name" {
  description = "DynamoDB notifications table name"
  value       = module.dynamodb.notifications_table_name
}
