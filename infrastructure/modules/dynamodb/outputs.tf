output "notifications_table_name" {
  description = "DynamoDB notifications table name"
  value       = aws_dynamodb_table.notifications.name
}

output "notifications_table_arn" {
  description = "DynamoDB notifications table ARN"
  value       = aws_dynamodb_table.notifications.arn
}
