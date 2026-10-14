# S3 remote state backend.
# Pass bucket/region/dynamodb_table at init time:
#
#   terraform init \
#     -backend-config="bucket=<your-tfstate-bucket>" \
#     -backend-config="region=eu-central-1" \
#     -backend-config="dynamodb_table=chargeflow-tfstate-lock"
#
# The bucket is created by infrastructure/bootstrap.
terraform {
  backend "s3" {
    key = "dev/terraform.tfstate"
  }
}
