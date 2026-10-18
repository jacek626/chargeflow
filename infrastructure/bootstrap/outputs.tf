# Nazwa bucketu stanu - podajesz ją w environments/dev przy terraform init
# -backend-config="bucket=..." (lokalnie) oraz jako sekret TF_STATE_BUCKET w GitHub (CI).
output "state_bucket_name" {
  description = "S3 bucket name to pass to terraform init -backend-config"
  value       = aws_s3_bucket.tfstate.id # dla bucketu S3 id = jego nazwa
}

# Nazwa tabeli locków - do -backend-config="dynamodb_table=..." i sekretu TF_LOCK_TABLE w GitHub.
output "lock_table_name" {
  description = "DynamoDB table name to pass to terraform init -backend-config"
  value       = aws_dynamodb_table.tfstate_lock.name # atrybut name zasobu DynamoDB z main.tf
}
