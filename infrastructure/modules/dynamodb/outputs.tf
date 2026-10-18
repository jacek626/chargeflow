# Nazwa tabeli — przekazywana do Notification Service (konfiguracja) przez moduł ecs.
output "notifications_table_name" {
  description = "DynamoDB notifications table name"
  value       = aws_dynamodb_table.notifications.name
}

# ARN tabeli — używany w politykach IAM, by nadać serwisowi dostęp do tej tabeli.
output "notifications_table_arn" {
  description = "DynamoDB notifications table ARN"
  value       = aws_dynamodb_table.notifications.arn
}
