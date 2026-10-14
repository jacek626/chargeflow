provider "aws" {
  region = var.aws_region

  # Credentials are obtained from standard AWS credential mechanisms:
  #   - Environment variables: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
  #   - IAM task role (ECS Fargate)
  #   - GitHub Actions OIDC (CI/CD)
  #
  # Do NOT hardcode credentials here.

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
