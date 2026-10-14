module "iam_github" {
  source = "../../modules/iam-github"

  name_prefix = local.name_prefix
  github_repo = var.github_repo
}

module "network" {
  source = "../../modules/network"

  name_prefix = local.name_prefix
  environment = var.environment
}

module "ecr" {
  source = "../../modules/ecr"

  name_prefix = local.name_prefix
  environment = var.environment
}

module "messaging" {
  source = "../../modules/messaging"

  name_prefix = local.name_prefix
  environment = var.environment
}

module "cognito" {
  source = "../../modules/cognito"

  name_prefix   = local.name_prefix
  environment   = var.environment
  callback_urls = var.cognito_callback_urls
  logout_urls   = var.cognito_logout_urls
}

module "dynamodb" {
  source = "../../modules/dynamodb"

  name_prefix = local.name_prefix
  environment = var.environment
}

module "ses" {
  source = "../../modules/ses"

  from_email  = var.ses_from_email
  environment = var.environment
}

module "rds" {
  source = "../../modules/rds"

  name_prefix       = local.name_prefix
  environment       = var.environment
  vpc_id            = module.network.vpc_id
  subnet_ids        = module.network.private_subnet_ids
  security_group_id = module.network.rds_sg_id
}

module "alb" {
  source = "../../modules/alb"

  name_prefix         = local.name_prefix
  environment         = var.environment
  vpc_id              = module.network.vpc_id
  public_subnet_ids   = module.network.public_subnet_ids
  security_group_id   = module.network.alb_sg_id
  acm_certificate_arn = var.acm_certificate_arn
}

module "ecs" {
  source = "../../modules/ecs"

  name_prefix       = local.name_prefix
  environment       = var.environment
  aws_region        = var.aws_region
  vpc_id            = module.network.vpc_id
  subnet_ids        = module.network.private_subnet_ids
  security_group_id = module.network.ecs_sg_id

  charging_session_image = var.charging_session_image
  notification_image     = var.notification_image

  target_group_arn = module.alb.charging_session_target_group_arn

  db_host                = module.rds.host
  db_port                = module.rds.port
  db_name                = module.rds.db_name
  db_username            = module.rds.db_username
  db_password_secret_arn = module.rds.db_password_secret_arn

  sns_topic_arn       = module.messaging.sns_topic_arn
  sqs_queue_url       = module.messaging.sqs_queue_url
  dynamodb_table_name = module.dynamodb.notifications_table_name
  ses_from_address    = var.ses_from_email
  cognito_jwks_uri    = module.cognito.jwks_uri

  task_cpu      = var.ecs_task_cpu
  task_memory   = var.ecs_task_memory
  desired_count = var.ecs_desired_count
}

module "frontend" {
  source = "../../modules/frontend"

  name_prefix         = local.name_prefix
  environment         = var.environment
  acm_certificate_arn = var.frontend_acm_certificate_arn
  domain_aliases      = var.frontend_domain_aliases
}

module "monitoring" {
  source = "../../modules/monitoring"

  name_prefix        = local.name_prefix
  environment        = var.environment
  log_retention_days = var.log_retention_days
}
