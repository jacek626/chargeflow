# Data source: zwraca tożsamość (konto, ARN, user) poświadczeń, którymi działa Terraform.
# Potrzebujemy z niej tylko ID konta AWS, aby zbudować ARN-y w politykach IAM poniżej.
data "aws_caller_identity" "current" {}

# Lokalne wartości modułu — skrót, żeby nie powtarzać długiego wyrażenia w wielu miejscach.
locals {
  account_id = data.aws_caller_identity.current.account_id # 12-cyfrowe ID konta, używane w ARN-ach SQS/DynamoDB
}

# ── ECS Cluster ──────────────────────────────────────────────────────────────

# Klaster ECS — logiczny "kontener" na serwisy i taski. Przy Fargate nie ma tu żadnych
# serwerów (EC2) do zarządzania; klaster to tylko przestrzeń nazw dla 2 serwisów ChargeFlow V1.
resource "aws_ecs_cluster" "main" {
  name = "${var.name_prefix}-cluster" # np. chargeflow-dev-cluster

  # Container Insights — dodatkowe metryki CPU/RAM/sieci per task i serwis w CloudWatch (płatne).
  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = { Name = "${var.name_prefix}-cluster" }
}

# Capacity providers — skąd klaster bierze moc obliczeniową. Tylko FARGATE (serverless),
# bez FARGATE_SPOT (tańszy, ale AWS może przerwać task) — prościej i stabilniej w V1.
resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE"] # dozwolone providery w tym klastrze

  # Domyślna strategia dla serwisów bez własnej — 100% tasków na FARGATE.
  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1 # waga względna; przy jednym providerze i tak dostaje wszystko
  }
}

# ── CloudWatch Log Groups ─────────────────────────────────────────────────────

# Log group dla charging-session-service — tu driver awslogs wysyła stdout kontenera
# (Spring loguje JSON w formacie ECS). Ścieżka z sufiksem /ecs.
resource "aws_cloudwatch_log_group" "charging_session" {
  name = "/chargeflow/${var.environment}/charging-session-service/ecs"
  # UWAGA: retencja 30 dni zahardkodowana, niezależna od var.log_retention_days modułu monitoring,
  # który tworzy osobne (puste) log groups bez sufiksu /ecs — duplikacja konfiguracji.
  retention_in_days = 30

  tags = { Name = "${var.name_prefix}-css-logs" }
}

# Log group dla notification-service — analogicznie jak wyżej.
resource "aws_cloudwatch_log_group" "notification" {
  name = "/chargeflow/${var.environment}/notification-service/ecs"
  # UWAGA: jak wyżej — hardkodowane 30 dni, osobno od modułu monitoring.
  retention_in_days = 30

  tags = { Name = "${var.name_prefix}-ns-logs" }
}

# ── IAM — Task Execution Role ─────────────────────────────────────────────────

# Execution role — używa jej AGENT ECS (infrastruktura), nie Twój kod. Potrzebna, by przed
# startem kontenera: pobrać obraz z ECR, utworzyć strumień logów i wstrzyknąć sekrety.
resource "aws_iam_role" "execution" {
  name = "${var.name_prefix}-ecs-execution-role"

  # Trust policy: KTO może przyjąć tę rolę — tylko usługa ECS Tasks (sts:AssumeRole).
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" } # principal usługowy ECS
    }]
  })
}

# Podpięcie polityki zarządzanej przez AWS: ecr:GetAuthorizationToken, ecr:BatchGetImage,
# logs:CreateLogStream, logs:PutLogEvents itd. — minimum do pull obrazu i logów awslogs.
resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Inline policy: pozwala agentowi ECS odczytać sekret z hasłem do RDS, by wstawić go jako
# env DB_PASSWORD. Brak tego uprawnienia = ResourceInitializationError przy starcie taska.
resource "aws_iam_role_policy" "execution_secrets" {
  name = "read-db-secret"
  role = aws_iam_role.execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"] # tylko odczyt wartości sekretu
      Resource = [var.db_password_secret_arn]      # zawężone do jednego konkretnego sekretu (least privilege)
    }]
  })
}

# ── IAM — Task Role (runtime AWS API access) ──────────────────────────────────

# Task role — rolę przyjmuje APLIKACJA w kontenerze; AWS SDK w Springu dostaje z niej
# tymczasowe poświadczenia (bez kluczy w kodzie) do wywołań SNS/SQS/DynamoDB/SES.
# UWAGA: jedna wspólna task role dla OBU serwisów — charging-session-service dostaje
# uprawnienia SQS/DynamoDB/SES, których nie potrzebuje. Lepiej: osobna rola per serwis.
resource "aws_iam_role" "task" {
  name = "${var.name_prefix}-ecs-task-role"

  # Trust policy: jak w execution role — przyjąć może tylko usługa ECS Tasks.
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
}

# Inline policy z uprawnieniami runtime aplikacji — każdy Statement (Sid) to jedna usługa AWS.
resource "aws_iam_role_policy" "task" {
  name = "chargeflow-runtime"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # Publikacja na topic SNS domain events: CSS (outbox → zdarzenia sesji),
      # NS (zdarzenia wyniku powiadomienia). Zawężone do jednego ARN-u topicu.
      {
        Sid      = "SNSPublish"
        Effect   = "Allow"
        Action   = ["sns:Publish"]
        Resource = [var.sns_topic_arn]
      },
      # Konsumpcja SQS przez notification-service: pobranie, usunięcie po przetworzeniu,
      # odczyt atrybutów kolejki.
      # UWAGA: wildcard `:*` = WSZYSTKIE kolejki w koncie i regionie, nie tylko kolejka notifications.
      {
        Sid    = "SQSConsume"
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes"
        ]
        Resource = ["arn:aws:sqs:${var.aws_region}:${local.account_id}:*"]
      },
      # Zapis/odczyt historii powiadomień w DynamoDB przez notification-service.
      # UWAGA: `table/*` = każda tabela w koncie i regionie; powinien być ARN tabeli notifications.
      {
        Sid    = "DynamoDB"
        Effect = "Allow"
        Action = [
          "dynamodb:PutItem",
          "dynamodb:GetItem",
          "dynamodb:Query",
          "dynamodb:UpdateItem"
        ]
        Resource = ["arn:aws:dynamodb:${var.aws_region}:${local.account_id}:table/*"]
      },
      # Wysyłka e-maili przez SES (notification-service). Resource "*" = dowolna
      # zweryfikowana tożsamość nadawcy w koncie.
      {
        Sid      = "SESSend"
        Effect   = "Allow"
        Action   = ["ses:SendEmail", "ses:SendRawEmail"]
        Resource = ["*"]
      },
      # UWAGA: zbędne — logi wysyła driver awslogs z EXECUTION role, a aplikacja nie
      # publikuje metryk przez PutMetricData. Do usunięcia w ramach least privilege.
      {
        Sid    = "CloudWatchMetrics"
        Effect = "Allow"
        Action = [
          "cloudwatch:PutMetricData",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = ["*"]
      }
    ]
  })
}

# ── Task Definition — charging-session-service ────────────────────────────────

# Task definition = "przepis" na uruchomienie kontenera (obraz, CPU/RAM, env, sekrety, logi).
# Każda zmiana tworzy nową REWIZJĘ (family:N); serwis wskazuje konkretną rewizję.
resource "aws_ecs_task_definition" "charging_session" {
  family                   = "${var.name_prefix}-charging-session-service" # nazwa rodziny rewizji
  requires_compatibilities = ["FARGATE"]                                   # walidacja: definicja musi spełniać wymagania Fargate
  network_mode             = "awsvpc"                                      # każdy task dostaje własny ENI i prywatne IP w VPC (wymagane na Fargate)
  cpu                      = var.task_cpu                                  # jednostki CPU dla całego taska (256 = 0.25 vCPU)
  memory                   = var.task_memory                               # MiB; musi tworzyć dozwoloną parę z cpu (256 → 512/1024/2048)
  execution_role_arn       = aws_iam_role.execution.arn                    # rola agenta ECS: pull z ECR, logi, sekrety
  task_role_arn            = aws_iam_role.task.arn                         # rola aplikacji: wywołania AWS SDK w runtime

  # Definicje kontenerów jako JSON (format API ECS) — jeden kontener na task.
  container_definitions = jsonencode([
    {
      name      = "charging-session-service" # nazwa kontenera; musi zgadzać się z load_balancer.container_name w serwisie
      image     = var.charging_session_image # pełny URI obrazu w ECR, z tagiem = SHA commita z deploy.yml
      essential = true                       # jeśli ten kontener padnie, ECS zatrzymuje cały task

      # Port, na którym Spring Boot słucha w kontenerze; przy awsvpc to też port rejestrowany w target group ALB.
      portMappings = [{ containerPort = 8080, protocol = "tcp" }]

      # Zmienne środowiskowe — trafiają do placeholderów ${...} w application.yml.
      # UWAGA: brak CORS_ALLOWED_ORIGINS — Spring użyje domyślnego http://localhost:4200,4201,
      # więc przeglądarka z domeny CloudFront zostanie zablokowana przez CORS.
      environment = [
        { name = "AWS_REGION", value = var.aws_region },                                               # chargeflow.aws.region — region klientów AWS SDK
        { name = "DB_URL", value = "jdbc:postgresql://${var.db_host}:${var.db_port}/${var.db_name}" }, # spring.datasource.url — JDBC do RDS PostgreSQL
        { name = "DB_USERNAME", value = var.db_username },                                             # spring.datasource.username
        { name = "SNS_ENABLED", value = "true" },                                                      # chargeflow.sns.enabled — włącza publikację outboxu do SNS (lokalnie false)
        { name = "SNS_TOPIC_ARN", value = var.sns_topic_arn },                                         # chargeflow.sns.topic-arn — topic domain events
        { name = "COGNITO_JWKS_URI", value = var.cognito_jwks_uri }                                    # jwk-set-uri — klucze publiczne Cognito do walidacji JWT
      ]

      # Sekrety: ECS (execution role) przy starcie pobiera wartość z Secrets Manager i wstawia
      # ją jako env DB_PASSWORD (spring.datasource.password). W task definition widać tylko ARN.
      secrets = [
        { name = "DB_PASSWORD", valueFrom = var.db_password_secret_arn }
      ]

      # Logi: driver awslogs wysyła stdout/stderr kontenera do CloudWatch Logs.
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.charging_session.name # docelowa log group
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs" # strumień: ecs/<nazwa-kontenera>/<task-id>
        }
      }

      # Container health check — ECS wykonuje komendę WEWNĄTRZ kontenera; 3 porażki = UNHEALTHY → restart taska.
      # UWAGA: obraz eclipse-temurin:25-jre nie zawiera curl — komenda zawsze się nie powiedzie,
      # co grozi pętlą restartów. Naprawa: doinstalować curl w Dockerfile albo usunąć ten check.
      healthCheck = {
        command     = ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"]
        interval    = 30 # co ile sekund sprawdzać
        timeout     = 5  # ile sekund czekać na odpowiedź
        retries     = 3  # ile kolejnych porażek oznacza UNHEALTHY
        startPeriod = 60 # okres karencji na start JVM/Springa — porażki w tym czasie się nie liczą
      }
    }
  ])

  tags = { Name = "${var.name_prefix}-charging-session-service" }
}

# ── Task Definition — notification-service ───────────────────────────────────

# Task definition notification-service — analogiczna do CSS, ale: port 8081, env SQS/DynamoDB/SES,
# brak sekcji secrets (serwis nie używa bazy relacyjnej).
resource "aws_ecs_task_definition" "notification" {
  family                   = "${var.name_prefix}-notification-service"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu # te same rozmiary co CSS (wspólne zmienne)
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn # UWAGA: ta sama task role co CSS (patrz komentarz przy aws_iam_role.task)

  container_definitions = jsonencode([
    {
      name      = "notification-service"
      image     = var.notification_image # URI obrazu w ECR z tagiem = SHA commita
      essential = true

      # Spring słucha na 8081 (server.port); brak ALB — port używany tylko wewnątrz VPC / przez health check.
      portMappings = [{ containerPort = 8081, protocol = "tcp" }]

      environment = [
        { name = "AWS_REGION", value = var.aws_region },                            # chargeflow.aws.region
        { name = "SQS_ENABLED", value = "true" },                                   # chargeflow.sqs.enabled — włącza poller kolejki (lokalnie false)
        { name = "SQS_QUEUE_URL", value = var.sqs_queue_url },                      # chargeflow.sqs.queue-url — kolejka z eventami z SNS
        { name = "DYNAMODB_NOTIFICATIONS_TABLE", value = var.dynamodb_table_name }, # chargeflow.dynamodb.table-name
        { name = "SES_ENABLED", value = "true" },                                   # chargeflow.ses.enabled — prawdziwa wysyłka e-maili przez SES
        { name = "SES_FROM_ADDRESS", value = var.ses_from_address },                # chargeflow.ses.from-address — zweryfikowany nadawca
        { name = "SNS_ENABLED", value = "true" },                                   # chargeflow.sns.enabled — publikacja zdarzeń wyniku (sent/failed)
        # UWAGA: to ARN topicu domain events, który ma subskrypcję do kolejki notifications —
        # zdarzenia wyniku wracają do tej samej kolejki (pętla); potrzebny osobny topic lub filter policy.
        { name = "SNS_NOTIFICATION_TOPIC_ARN", value = var.sns_topic_arn }, # chargeflow.sns.notification-topic-arn
        { name = "COGNITO_JWKS_URI", value = var.cognito_jwks_uri }         # obecnie nieużywane: application.yml notification-service nie ma jwk-set-uri
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.notification.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ecs"
        }
      }

      # UWAGA: jak w CSS — curl nie istnieje w eclipse-temurin:25-jre, health check będzie failował.
      healthCheck = {
        command     = ["CMD-SHELL", "curl -f http://localhost:8081/actuator/health || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 60
      }
    }
  ])

  tags = { Name = "${var.name_prefix}-notification-service" }
}

# ── ECS Services ──────────────────────────────────────────────────────────────

# ECS service — utrzymuje zadaną liczbę działających tasków (restartuje padnięte),
# robi rolling deploy i rejestruje taski w target group ALB.
resource "aws_ecs_service" "charging_session" {
  name            = "${var.name_prefix}-charging-session-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.charging_session.arn # ARN konkretnej rewizji (family:N)
  desired_count   = var.desired_count                            # ile tasków ma stale działać (1 w dev)
  launch_type     = "FARGATE"                                    # serverless; uwaga: launch_type wyklucza capacity_provider_strategy

  # Sieć tasków (wymagane dla awsvpc).
  network_configuration {
    subnets          = var.subnet_ids          # prywatne subnety — taski niedostępne bezpośrednio z internetu
    security_groups  = [var.security_group_id] # ecs-sg: ruch przychodzący tylko z ALB
    assign_public_ip = false                   # bez publicznego IP; wyjście do ECR/Cognito/SNS przez NAT Gateway
  }

  # Integracja z ALB: ECS sam rejestruje IP każdego nowego taska w target group i wyrejestrowuje przy stopie.
  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = "charging-session-service" # musi pasować do name w container_definitions
    container_port   = 8080                       # musi pasować do containerPort
  }

  # Rolling deploy "najpierw nowy, potem stary": min 100% zdrowych, max 200% w trakcie —
  # przy desired_count=1 ECS startuje drugi task i gasi stary dopiero gdy nowy jest zdrowy.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  # Terraform ignoruje zmianę rewizji task definition w serwisie (zakłada, że podmienia ją CD).
  # UWAGA: deploy.yml robi tylko terraform apply, bez `aws ecs update-service` — nowa rewizja
  # z nowym obrazem powstaje, ale serwis dalej uruchamia starą. Nowy kod nigdy się nie wdraża.
  lifecycle {
    ignore_changes = [task_definition]
  }

  tags = { Name = "${var.name_prefix}-charging-session-service" }
}

# Serwis notification-service — konsument SQS, bez bloku load_balancer (nie wystawia publicznego API).
resource "aws_ecs_service" "notification" {
  name            = "${var.name_prefix}-notification-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.notification.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids          # te same prywatne subnety co CSS
    security_groups  = [var.security_group_id] # ten sam security group co CSS
    assign_public_ip = false                   # wyjście do SQS/DynamoDB/SES przez NAT Gateway
  }

  # Jak wyżej: rolling deploy bez przestoju.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  # UWAGA: jak w CSS — bez `aws ecs update-service` w deploy.yml nowy obraz się nie wdroży.
  lifecycle {
    ignore_changes = [task_definition]
  }

  tags = { Name = "${var.name_prefix}-notification-service" }
}
