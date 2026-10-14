provider "aws" {
  region = var.aws_region

  # Credentials and endpoint override are supplied via environment variables:
  #   AWS_ACCESS_KEY_ID     = test
  #   AWS_SECRET_ACCESS_KEY = test
  #   AWS_ENDPOINT_URL      = http://localhost:4566
  #
  # These are LocalStack dummy values — NOT real AWS credentials.

  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true
  s3_use_path_style           = true

  default_tags {
    tags = local.common_tags
  }
}

locals {
  name_prefix = "${var.project_name}-${var.environment}"

  common_tags = {
    Project     = "ChargeFlow"
    Environment = var.environment
    ManagedBy   = "Terraform"
  }
}
