# ARN tematu — Charging Session Service publikuje na niego zdarzenia (konfiguracja + uprawnienie sns:Publish).
output "sns_topic_arn" {
  description = "ARN of the domain events SNS topic"
  value       = aws_sns_topic.domain_events.arn
}

# Nazwa tematu — informacyjnie / do wyszukiwania w konsoli.
output "sns_topic_name" {
  description = "Name of the domain events SNS topic"
  value       = aws_sns_topic.domain_events.name
}

# ARN kolejki — używany w politykach IAM (sqs:ReceiveMessage/DeleteMessage).
output "sqs_queue_arn" {
  description = "ARN of the notifications SQS queue"
  value       = aws_sqs_queue.notifications.arn
}

# URL kolejki — tego adresu używa SDK w Notification Service do odbierania wiadomości.
output "sqs_queue_url" {
  description = "URL of the notifications SQS queue"
  value       = aws_sqs_queue.notifications.url
}

# ARN DLQ — np. do przyszłego alarmu lub ręcznego redrive.
output "sqs_dlq_arn" {
  description = "ARN of the notifications DLQ"
  value       = aws_sqs_queue.notifications_dlq.arn
}

# URL DLQ — do ręcznego podglądu wiadomości, które nie przeszły przetwarzania.
output "sqs_dlq_url" {
  description = "URL of the notifications DLQ"
  value       = aws_sqs_queue.notifications_dlq.url
}
