module "messaging" {
  source = "../../modules/messaging"

  name_prefix = local.name_prefix
  environment = var.environment
}

module "dynamodb" {
  source = "../../modules/dynamodb"

  name_prefix = local.name_prefix
  environment = var.environment
}

module "ses" {
  source = "../../modules/ses"

  from_email  = "noreply@chargeflow.pl"
  environment = var.environment
}

module "monitoring" {
  source = "../../modules/monitoring"

  name_prefix        = local.name_prefix
  environment        = var.environment
  log_retention_days = 7
}
