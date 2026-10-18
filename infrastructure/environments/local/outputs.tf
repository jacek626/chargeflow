# output = wartość wypisywana po apply (terraform output <nazwa>); czytają ją ludzie, CI i skrypty.
# start-local.sh czyta te outputy (terraform output -raw) i eksportuje je jako env dla serwisów.
# UWAGA: brak outputów cognito_user_pool_id/cognito_client_id, a scripts/create-local-user.sh
# i get-local-token.sh ich oczekują - te skrypty zakończą się błędem.
#
# ARN topicu SNS - Charging Session Service publikuje tu zdarzenia.
output "sns_topic_arn" {
  description = "Domain events SNS topic ARN"
  value       = module.messaging.sns_topic_arn
}

# Nazwa topicu SNS (wygodna do aws sns ... --endpoint-url).
output "sns_topic_name" {
  description = "Domain events SNS topic name"
  value       = module.messaging.sns_topic_name
}

# URL kolejki SQS - z niej czyta Notification Service.
output "sqs_queue_url" {
  description = "Notifications SQS queue URL"
  value       = module.messaging.sqs_queue_url
}

# ARN kolejki SQS.
output "sqs_queue_arn" {
  description = "Notifications SQS queue ARN"
  value       = module.messaging.sqs_queue_arn
}

# URL kolejki DLQ - wiadomości, których nie udało się przetworzyć.
output "sqs_dlq_url" {
  description = "Notifications DLQ URL"
  value       = module.messaging.sqs_dlq_url
}

# ARN kolejki DLQ.
output "sqs_dlq_arn" {
  description = "Notifications DLQ ARN"
  value       = module.messaging.sqs_dlq_arn
}

# Nazwa tabeli DynamoDB z powiadomieniami.
output "dynamodb_notifications_table_name" {
  description = "DynamoDB notifications table name"
  value       = module.dynamodb.notifications_table_name
}
