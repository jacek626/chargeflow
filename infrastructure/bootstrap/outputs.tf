output "state_bucket_name" {
  description = "S3 bucket name to pass to terraform init -backend-config"
  value       = aws_s3_bucket.tfstate.id
}

output "lock_table_name" {
  description = "DynamoDB table name to pass to terraform init -backend-config"
  value       = aws_dynamodb_table.tfstate_lock.name
}
