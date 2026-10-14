output "sns_topic_arn" {
  description = "ARN of the domain events SNS topic"
  value       = aws_sns_topic.domain_events.arn
}

output "sns_topic_name" {
  description = "Name of the domain events SNS topic"
  value       = aws_sns_topic.domain_events.name
}

output "sqs_queue_arn" {
  description = "ARN of the notifications SQS queue"
  value       = aws_sqs_queue.notifications.arn
}

output "sqs_queue_url" {
  description = "URL of the notifications SQS queue"
  value       = aws_sqs_queue.notifications.url
}

output "sqs_dlq_arn" {
  description = "ARN of the notifications DLQ"
  value       = aws_sqs_queue.notifications_dlq.arn
}

output "sqs_dlq_url" {
  description = "URL of the notifications DLQ"
  value       = aws_sqs_queue.notifications_dlq.url
}
