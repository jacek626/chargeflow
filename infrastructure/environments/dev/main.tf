# Root module środowiska dev (prawdziwe AWS): składa całą infrastrukturę V1 z modułów
# w ../../modules. Outputy jednego modułu (module.X.Y) są inputami innego - z tych referencji
# Terraform buduje graf zależności i sam ustala kolejność tworzenia (bez depends_on).
# UWAGA: var.environment nie ma wartości domyślnej, a deploy.yml jej nie przekazuje
# (ani -var, ani TF_VAR_environment) - nieinteraktywny apply w CI skończy się błędem.
#
# IAM dla GitHub Actions: OIDC provider + rola, którą CI/CD przyjmuje bez stałych
# kluczy dostępu (krótkotrwałe tokeny STS).
module "iam_github" {
  source = "../../modules/iam-github" # lokalny katalog z kodem modułu; zmiana source wymaga ponownego terraform init

  name_prefix = local.name_prefix # "chargeflow-dev" z locals (providers.tf) - prefiks nazw zasobów
  github_repo = var.github_repo   # org/repo - trust policy roli wpuści tylko tokeny OIDC z tego repozytorium
}

# Sieć: VPC, subnety publiczne/prywatne, NAT, security groups.
# rds/alb/ecs dostają z niego ID przez outputy module.network.*.
module "network" {
  source = "../../modules/network" # kod modułu sieci

  name_prefix = local.name_prefix # nazwy typu chargeflow-dev-vpc
  environment = var.environment   # "dev" - trafia do tagów i nazw
}

# ECR: prywatne repozytoria obrazów Docker dla obu serwisów Spring Boot.
module "ecr" {
  source = "../../modules/ecr" # kod modułu ECR

  name_prefix = local.name_prefix # np. chargeflow-dev-charging-session-service
  environment = var.environment   # środowisko do tagów
}

# Messaging: topic SNS na zdarzenia domenowe + kolejka SQS (z DLQ) dla Notification Service.
# Charging Session publikuje do SNS, SNS dostarcza kopię do SQS (fan-out).
module "messaging" {
  source = "../../modules/messaging" # kod modułu SNS/SQS

  name_prefix = local.name_prefix # prefiks nazw topicu i kolejek
  environment = var.environment   # środowisko do tagów
}

# Cognito User Pool: rejestracja/logowanie użytkowników Angulara i wydawanie tokenów JWT.
module "cognito" {
  source = "../../modules/cognito" # kod modułu Cognito

  name_prefix   = local.name_prefix         # nazwa User Poola i klienta aplikacji
  environment   = var.environment           # środowisko do tagów
  callback_urls = var.cognito_callback_urls # dozwolone adresy powrotu po logowaniu OAuth2 - inne Cognito odrzuci
  logout_urls   = var.cognito_logout_urls   # dozwolone adresy przekierowania po wylogowaniu
}

# DynamoDB: tabela na powiadomienia zapisywane przez Notification Service.
module "dynamodb" {
  source = "../../modules/dynamodb" # kod modułu DynamoDB

  name_prefix = local.name_prefix # nazwa tabeli np. chargeflow-dev-notifications
  environment = var.environment   # środowisko do tagów
}

# SES: tożsamość nadawcy e-maili z powiadomieniami (adres trzeba zweryfikować linkiem z maila).
module "ses" {
  source = "../../modules/ses" # kod modułu SES

  from_email  = var.ses_from_email # adres nadawcy; ta sama wartość trafia niżej do ECS jako ses_from_address
  environment = var.environment    # środowisko do tagów
}

# RDS PostgreSQL dla Charging Session Service, w prywatnych subnetach (bez dostępu z internetu).
# Hasło generuje moduł (provider random) i zapisuje w Secrets Manager.
module "rds" {
  source = "../../modules/rds" # kod modułu RDS

  name_prefix       = local.name_prefix                 # prefiks nazw instancji/subnet group
  environment       = var.environment                   # środowisko do tagów
  vpc_id            = module.network.vpc_id             # VPC z modułu network (output -> input = zależność)
  subnet_ids        = module.network.private_subnet_ids # prywatne subnety -> DB subnet group; baza bez publicznego IP
  security_group_id = module.network.rds_sg_id          # SG wpuszczający 5432 tylko z tasków ECS
}

# Application Load Balancer: publiczny punkt wejścia do REST API Charging Session Service.
module "alb" {
  source = "../../modules/alb" # kod modułu ALB

  name_prefix         = local.name_prefix                # prefiks nazw ALB i target group
  environment         = var.environment                  # środowisko do tagów
  vpc_id              = module.network.vpc_id            # VPC, w której powstanie target group
  public_subnet_ids   = module.network.public_subnet_ids # ALB musi stać w publicznych subnetach, by był osiągalny z internetu
  security_group_id   = module.network.alb_sg_id         # SG ALB: ruch 80/443 z internetu
  acm_certificate_arn = var.acm_certificate_arn          # certyfikat TLS; pusty string = tylko HTTP (ruch nieszyfrowany)
}

# ECS Fargate: klaster + dwa serwisy (charging-session, notification) jako kontenery
# bez zarządzania serwerami. Zbiera wartości z niemal wszystkich pozostałych modułów.
module "ecs" {
  source = "../../modules/ecs" # kod modułu ECS

  name_prefix       = local.name_prefix                 # prefiks nazw klastra, serwisów i ról
  environment       = var.environment                   # środowisko do tagów
  aws_region        = var.aws_region                    # region dla AWS SDK i sterownika logów w kontenerach
  vpc_id            = module.network.vpc_id             # VPC, w której działają taski
  subnet_ids        = module.network.private_subnet_ids # taski w prywatnych subnetach (wyjście do internetu przez NAT)
  security_group_id = module.network.ecs_sg_id          # SG tasków: ruch przychodzący tylko z ALB

  # Obrazy Docker (pełne URI z ECR); deploy.yml podmienia je przez -var przy każdym deployu
  charging_session_image = var.charging_session_image # obraz Charging Session Service
  notification_image     = var.notification_image     # obraz Notification Service

  # Target group ALB - ECS rejestruje w niej taski charging-session (tylko ten serwis wystawia REST API)
  target_group_arn = module.alb.charging_session_target_group_arn

  # Dane połączenia z bazą -> zmienne środowiskowe kontenera charging-session
  db_host                = module.rds.host                   # adres (host) instancji RDS
  db_port                = module.rds.port                   # port PostgreSQL (5432)
  db_name                = module.rds.db_name                # nazwa bazy w instancji RDS
  db_username            = module.rds.db_username            # użytkownik główny bazy
  db_password_secret_arn = module.rds.db_password_secret_arn # NIE hasło, tylko ARN sekretu - ECS wstrzyknie DB_PASSWORD z Secrets Manager

  # Integracje AWS używane przez serwisy (trafiają jako env do kontenerów)
  sns_topic_arn       = module.messaging.sns_topic_arn           # topic, do którego Charging Session publikuje zdarzenia
  sqs_queue_url       = module.messaging.sqs_queue_url           # kolejka, z której czyta Notification Service
  dynamodb_table_name = module.dynamodb.notifications_table_name # tabela, do której Notification zapisuje powiadomienia
  ses_from_address    = var.ses_from_email                       # nadawca e-maili wysyłanych przez SES
  cognito_jwks_uri    = module.cognito.jwks_uri                  # klucze publiczne Cognito do walidacji JWT w Spring Security

  # Rozmiar i liczba tasków - wprost przekłada się na koszt Fargate
  task_cpu      = var.ecs_task_cpu      # jednostki CPU (256 = 0.25 vCPU)
  task_memory   = var.ecs_task_memory   # MiB RAM; musi być dozwoloną kombinacją z CPU
  desired_count = var.ecs_desired_count # ile kopii każdego serwisu ma działać jednocześnie
}

# Frontend: bucket S3 z buildem Angulara + dystrybucja CloudFront (CDN, HTTPS) przed nim.
module "frontend" {
  source = "../../modules/frontend" # kod modułu frontendu

  name_prefix         = local.name_prefix                # prefiks nazwy bucketu
  environment         = var.environment                  # środowisko do tagów
  acm_certificate_arn = var.frontend_acm_certificate_arn # certyfikat CloudFront - MUSI być z us-east-1; pusty = domyślny *.cloudfront.net
  domain_aliases      = var.frontend_domain_aliases      # własne domeny; pusta lista = tylko domena cloudfront.net
}

# Monitoring: grupy logów CloudWatch, do których kontenery ECS wysyłają logi.
module "monitoring" {
  source = "../../modules/monitoring" # kod modułu monitoringu

  name_prefix        = local.name_prefix      # prefiks nazw grup logów
  environment        = var.environment        # środowisko do tagów
  log_retention_days = var.log_retention_days # ile dni trzymać logi; dłużej = drożej
}
