# 03 — ECR, ECS Fargate i ALB: jak kontener Springa trafia do chmury

> Materiał oparty na kodzie: `infrastructure/modules/ecr`, `infrastructure/modules/ecs`,
> `infrastructure/modules/alb`, `infrastructure/environments/dev/main.tf`, Dockerfile obu
> serwisów oraz `.github/workflows/deploy.yml`.

---

## 1. Po co to jest

Lokalnie uruchamiasz `./mvnw spring-boot:run` i aplikacja nasłuchuje na `localhost:8080`.
W AWS trzeba odpowiedzieć na cztery pytania:

| Pytanie | Usługa AWS | Analogia ze świata Java |
|---|---|---|
| Gdzie trzymam zbudowany artefakt? | **ECR** (Elastic Container Registry) | Nexus/Artifactory, tylko dla obrazów Dockera |
| Kto uruchamia i pilnuje procesu? | **ECS** (Elastic Container Service) | systemd / supervisor / „Kubernetes light” |
| Na jakich maszynach? | **Fargate** | „serverless” dla kontenerów — nie widzisz żadnego EC2 |
| Jak ruch z internetu trafia do procesu? | **ALB** (Application Load Balancer) | nginx reverse proxy + health checki |

Analogia: ECR to magazyn z gotowymi daniami (obrazami), ECS to kierownik kuchni, który
pilnuje, żeby zawsze stała określona liczba kucharzy (tasków), Fargate to agencja pracy
dostarczająca kucharzy bez etatu, a ALB to kelner przyjmujący zamówienia od gości z ulicy
i rozdający je tylko tym kucharzom, którzy są zdrowi.

---

## 2. Kluczowe pojęcia AWS

- **ECR repository** — prywatne repozytorium obrazów w regionie. URL ma postać
  `<account>.dkr.ecr.<region>.amazonaws.com/<nazwa>`. Push/pull wymaga uwierzytelnienia IAM.
- **Lifecycle policy (ECR)** — reguły automatycznego usuwania starych obrazów (oszczędność storage).
- **Image scanning** — skanowanie obrazu pod kątem CVE (basic scanning przy pushu).
- **ECS cluster** — logiczna grupa, w której działają serwisy i taski. Przy Fargate to tylko „namespace”.
- **Task definition** — wersjonowany (rewizje: `family:1`, `family:2`…) opis tego, *co* uruchomić:
  obraz, CPU/RAM, porty, zmienne środowiskowe, sekrety, logowanie, health check, role IAM.
  Odpowiednik `docker run ...` zapisanego jako JSON.
- **Task** — uruchomiona instancja task definition (1..n kontenerów współdzielących sieć).
- **ECS service** — utrzymuje `desired_count` tasków, wymienia niezdrowe, robi rolling deploy,
  rejestruje taski w target group ALB.
- **Fargate** — launch type, w którym AWS dostarcza „mikro‑VM” o zadanym CPU/RAM. Wymaga
  `network_mode = "awsvpc"`: każdy task dostaje własny ENI (interfejs sieciowy) i prywatny IP w subnecie.
- **Capacity provider** — strategia, skąd brać moc obliczeniową (`FARGATE`, `FARGATE_SPOT`, EC2 ASG).
- **Execution role** — rola, której używa *agent ECS* (nie Twoja aplikacja!) przy starcie taska:
  pull z ECR, zapis logów do CloudWatch, pobranie sekretów do zmiennych środowiskowych.
- **Task role** — rola, którą dostaje *Twoja aplikacja* w runtime. AWS SDK w Springu
  (default credentials provider chain) pobiera z niej tymczasowe credentiale.
- **ALB** — load balancer warstwy 7 (HTTP/HTTPS). Składa się z:
  - **listenera** (port + protokół, np. `HTTP:80`) z regułami/akcjami (`forward`, `redirect`…),
  - **target group** — zbioru celów (u nas adresów IP tasków) + konfiguracji **health checka**.
- **Security group** — stanowy firewall na poziomie ENI; reguły mogą wskazywać inną SG jako źródło.

---

## 3. Jak to działa w ChargeFlow

### 3.1 Obraz Dockera — punkt wyjścia

Oba serwisy mają identyczny, dwuetapowy (multi‑stage) Dockerfile:

```dockerfile
# charging-session-service/Dockerfile:2-8
FROM eclipse-temurin:25-jdk AS build
...
RUN ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw package -DskipTests -B

# charging-session-service/Dockerfile:11-17
FROM eclipse-temurin:25-jre
RUN groupadd -r chargeflow && useradd -r -g chargeflow -s /usr/sbin/nologin chargeflow
...
USER chargeflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- Etap `build` ma JDK i Mavena; najpierw kopiuje tylko `pom.xml` i robi `go-offline`, żeby warstwa
  z zależnościami była cache’owana, dopóki nie zmieni się POM.
- Etap runtime ma tylko JRE i fat-jar → mniejszy obraz, mniejsza powierzchnia ataku.
- Proces działa jako użytkownik `chargeflow`, nie root.
- `notification-service/Dockerfile:16` różni się tylko `EXPOSE 8081`.
- `EXPOSE` jest dokumentacyjne — o porcie w AWS decyduje `portMappings` w task definition.

Ważne: obraz **nie zawiera żadnej konfiguracji środowiskowej**. Wszystko przychodzi ze zmiennych
środowiskowych, które Spring rozwiązuje w `application.yml`, np.
`charging-session-service/src/main/resources/application.yml:6-8`:

```yaml
url: ${DB_URL:jdbc:postgresql://localhost:5432/chargeflow}
username: ${DB_USERNAME:chargeflow}
password: ${DB_PASSWORD:chargeflow}
```

### 3.2 ECR — `infrastructure/modules/ecr/main.tf`

```hcl
# infrastructure/modules/ecr/main.tf:4-7
repos = {
  charging_session_service = "${var.name_prefix}-charging-session-service"
  notification_service     = "${var.name_prefix}-notification-service"
}
```

- Dwa repozytoria tworzone przez `for_each` (`main.tf:27-42`). Dla `dev` nazwy to
  `chargeflow-dev-charging-session-service` i `chargeflow-dev-notification-service`
  (`name_prefix` = `"${var.project_name}-${var.environment}"`, `environments/dev/providers.tf:22`).
- `image_tag_mutability = "MUTABLE"` (`main.tf:34`) — ten sam tag (np. `latest`) można nadpisać.
  CI i tak taguje obrazy SHA commita (`deploy.yml:49`), więc tagi są de facto unikalne.
- `scan_on_push = true` (`main.tf:38`) — basic scanning CVE przy każdym pushu.
- Lifecycle policy (`main.tf:10-23`): `countType = "imageCountMoreThan"`, `countNumber = 10`
  (`variables.tf:19`), `tagStatus = "any"` → trzymamy 10 najnowszych obrazów, starsze wygasają.
- Outputy `repository_url` (`outputs.tf`) są wystawione w `environments/dev/outputs.tf:27-36`.

### 3.3 ECS cluster — `infrastructure/modules/ecs/main.tf`

```hcl
# infrastructure/modules/ecs/main.tf:14-37
resource "aws_ecs_cluster" "main" {
  name = "${var.name_prefix}-cluster"
  setting { name = "containerInsights"; value = "enabled" }
}
resource "aws_ecs_cluster_capacity_providers" "main" {
  capacity_providers = ["FARGATE"]
  default_capacity_provider_strategy { capacity_provider = "FARGATE"; weight = 1 }
}
```

- Container Insights = dodatkowe metryki CPU/RAM/sieci per task w CloudWatch (płatne, patrz sekcja 6).
- Tylko `FARGATE` (bez `FARGATE_SPOT`) — prościej, ale drożej.

### 3.4 Log groups

`main.tf:43-59` tworzy `/chargeflow/dev/charging-session-service/ecs` i
`/chargeflow/dev/notification-service/ecs`, retencja 30 dni. Task definitions wskazują je w
`logConfiguration` (driver `awslogs`, `main.tf:230-237`). Spring loguje JSON w formacie ECS
(`application.yml:48-51`: `logging.structured.format.console: ecs`) na stdout → driver `awslogs`
przesyła każdą linię do CloudWatch Logs.

> Uwaga: moduł `monitoring` (`infrastructure/modules/monitoring/main.tf:4-17`) tworzy *osobne*
> log groups `/chargeflow/dev/charging-session-service` (bez sufiksu `/ecs`), do których nic nie pisze.
> To duplikacja — logi kontenerów lądują w grupach z modułu `ecs`.

### 3.5 IAM: execution role vs task role

**Execution role** (`main.tf:65-100`) — zaufanie dla `ecs-tasks.amazonaws.com`, plus:

1. AWS‑managed `AmazonECSTaskExecutionRolePolicy` (`main.tf:81-84`) — `ecr:GetAuthorizationToken`,
   `ecr:BatchGetImage`, `logs:CreateLogStream`, `logs:PutLogEvents` itd.
2. Inline `read-db-secret` (`main.tf:88-100`) — `secretsmanager:GetSecretValue` **tylko** na ARN
   sekretu z hasłem do bazy. Bez tego agent ECS nie wstrzyknąłby `DB_PASSWORD`.

**Task role** (`main.tf:108-186`) — inline policy `chargeflow-runtime`:

| Sid | Akcje | Resource | Kto realnie używa |
|---|---|---|---|
| `SNSPublish` | `sns:Publish` | ARN topicu | CSS (outbox), NS (notification result events) |
| `SQSConsume` | Receive/Delete/GetQueueAttributes | `arn:aws:sqs:<region>:<account>:*` | NS |
| `DynamoDB` | Put/Get/Query/UpdateItem | `table/*` | NS |
| `SESSend` | `ses:SendEmail`, `SendRawEmail` | `*` | NS |
| `CloudWatchMetrics` | `PutMetricData`, `logs:*Stream/Events` | `*` | — |

> Uwaga (least privilege): CHANGELOG (CF-028) opisuje role jako „least-privilege”, ale:
> - **obie** task definitions używają tej samej task role (`main.tf:199` i `main.tf:266`), więc
>   charging-session-service ma uprawnienia do SES/DynamoDB/SQS, których nie potrzebuje;
> - SQS i DynamoDB mają wildcard na wszystkie kolejki/tabele w koncie i regionie;
> - uprawnienia `logs:*` w task role są zbędne — logi wysyła driver `awslogs` z execution role.
>
> Typowa poprawka: osobna task role per serwis i ARN-y konkretnych zasobów.

### 3.6 Task definition — charging-session-service (`main.tf:192-253`)

```hcl
family                   = "${var.name_prefix}-charging-session-service"
requires_compatibilities = ["FARGATE"]
network_mode             = "awsvpc"
cpu                      = var.task_cpu      # domyślnie 256  = 0.25 vCPU
memory                   = var.task_memory   # domyślnie 512 MiB
```

- `cpu/memory` muszą tworzyć dozwoloną parę Fargate (256 CPU → 512/1024/2048 MiB). Wartości
  przychodzą z `environments/dev/variables.tf:87-98` przez `main.tf:126-128`.
- Kontener `charging-session-service`, `essential = true` (jeśli padnie — pada cały task),
  `portMappings` 8080/tcp (`main.tf:209`).
- **Zmienne środowiskowe** (`main.tf:214-221`) — każda trafia do placeholdera w `application.yml`:

  | Env | Skąd w Terraform | Gdzie w Springu |
  |---|---|---|
  | `DB_URL` | `jdbc:postgresql://${module.rds.host}:${port}/${db_name}` | `spring.datasource.url` |
  | `DB_USERNAME` | `module.rds.db_username` | `spring.datasource.username` |
  | `SNS_ENABLED` / `SNS_TOPIC_ARN` | `"true"` / `module.messaging.sns_topic_arn` | `chargeflow.sns.*` |
  | `COGNITO_JWKS_URI` | `module.cognito.jwks_uri` | `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` |
  | `AWS_REGION` | `var.aws_region` | `chargeflow.aws.region` |

- **Sekrety** (`main.tf:225-227`):

  ```hcl
  secrets = [{ name = "DB_PASSWORD", valueFrom = var.db_password_secret_arn }]
  ```

  Przy starcie taska agent ECS (z execution role) wywołuje `GetSecretValue` i wstawia wartość jako
  zmienną środowiskową `DB_PASSWORD`. Hasło nie występuje w JSON-ie task definition — tylko ARN.
  Szczegóły w `04-rds-secrets-manager.md`.
- **Container health check** (`main.tf:242-248`): `curl -f http://localhost:8080/actuator/health`,
  co 30 s, timeout 5 s, 3 próby, `startPeriod = 60`.

> **Uwaga — prawdopodobny bug:** obraz bazowy `eclipse-temurin:25-jre` (Ubuntu) **nie zawiera
> `curl` ani `wget`** (sprawdzone: `docker run --rm --entrypoint sh eclipse-temurin:25-jre -c 'ls /usr/bin/curl'`
> → „No such file or directory”). Komenda `CMD-SHELL curl ...` zwróci błąd, ECS oznaczy kontener
> jako `UNHEALTHY`, a serwis będzie w pętli zabijać i uruchamiać taski. Opcje naprawy: doinstalować
> `curl` w Dockerfile, użyć health checka opartego o Javę, albo usunąć container health check i polegać
> tylko na health checku ALB (dla notification-service nie ma ALB — patrz niżej).

Task definition notification-service (`main.tf:259-312`) jest analogiczna: port 8081,
env `SQS_*`, `DYNAMODB_NOTIFICATIONS_TABLE`, `SES_*`, `SNS_NOTIFICATION_TOPIC_ARN`, **brak sekcji
`secrets`** (serwis nie ma bazy relacyjnej).

> Uwaga: żadna task definition nie ustawia `CORS_ALLOWED_ORIGINS`, więc charging-session-service
> w AWS użyje domyślnej wartości `http://localhost:4200,http://localhost:4201`
> (`application.yml:38`) — przeglądarka z domeny CloudFront zostanie zablokowana przez CORS.
> Dodatkowo frontend ma tylko `frontend/src/environments/environment.ts` z
> `apiBaseUrl: 'http://localhost:8080'` (brak wariantu produkcyjnego), a ALB bez certyfikatu działa
> po HTTP, podczas gdy CloudFront serwuje SPA po HTTPS (mixed content).

### 3.7 ECS services (`main.tf:318-378`)

```hcl
resource "aws_ecs_service" "charging_session" {
  desired_count   = var.desired_count        # 1 w dev
  launch_type     = "FARGATE"
  network_configuration {
    subnets          = var.subnet_ids         # prywatne subnety
    security_groups  = [var.security_group_id] # ecs-sg
    assign_public_ip = false
  }
  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = "charging-session-service"
    container_port   = 8080
  }
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  lifecycle { ignore_changes = [task_definition] }
}
```

- Taski w **prywatnych** subnetach bez publicznego IP; wychodzą do internetu (ECR, Cognito JWKS,
  SNS…) przez NAT Gateway (`modules/network/main.tf:78-87`, `78-81`).
- Blok `load_balancer` sprawia, że ECS sam rejestruje IP każdego nowego taska w target group
  i wyrejestrowuje go przy zatrzymaniu.
- `100% / 200%` = rolling deploy „najpierw nowy, potem stary”: przy `desired_count = 1` ECS
  uruchamia drugi task, czeka aż będzie zdrowy, dopiero wtedy gasi stary. Zero downtime, chwilowo
  podwójny koszt.
- Serwis notification-service (`main.tf:355-378`) **nie ma bloku `load_balancer`** — to konsument
  SQS, nie wystawia API publicznie.

> **Uwaga — deploy nie podmienia obrazu:** `ignore_changes = [task_definition]` (`main.tf:347-349`)
> każe Terraformowi ignorować zmianę rewizji w serwisie. Komentarz w CHANGELOG mówi „for CD
> deployments”, czyli zakłada, że rewizję podmienia ktoś inny (np. `aws ecs update-service`).
> Tymczasem `deploy.yml:93-99` robi wyłącznie `terraform apply -var charging_session_image=...`.
> Efekt: Terraform rejestruje nową rewizję task definition (a poprzednią deregistruje — staje się
> `INACTIVE`), ale serwis dalej wskazuje starą (tę z pierwszego `apply`) i nadal z niej uruchamia taski. W workflow brakuje kroku `aws ecs update-service --task-definition ...`
> (lub usunięcia `ignore_changes`).
>
> Dodatkowo pierwszy ręczny `terraform apply` (README, „First-time setup”) używa domyślnych obrazów
> `placeholder/charging-session-service:latest` (`environments/dev/variables.tf:74`), których nie ma
> w żadnym rejestrze — taski nie wystartują (`CannotPullContainerError`), a przez `ignore_changes`
> serwis zostanie przy tej rewizji.

### 3.8 ALB — `infrastructure/modules/alb/main.tf`

```hcl
# alb/main.tf:3-11
resource "aws_lb" "main" {
  internal           = false              # internet-facing
  load_balancer_type = "application"
  security_groups    = [var.security_group_id]   # alb-sg: 80 i 443 z 0.0.0.0/0
  subnets            = var.public_subnet_ids     # 2 publiczne subnety w 2 AZ
}
```

**Target group** (`alb/main.tf:15-35`):

- `target_type = "ip"` — obowiązkowe dla Fargate/awsvpc (rejestrujemy IP ENI taska, nie instancję EC2).
- `port = 8080`, `protocol = "HTTP"` — ALB → task po zwykłym HTTP wewnątrz VPC.
- Health check: `GET /actuator/health` na `traffic-port`, oczekiwany kod `200`, co 30 s,
  2 sukcesy = healthy, 3 porażki = unhealthy. Endpoint jest publiczny w Spring Security
  (`charging-session-service/src/main/java/pl/chargeflow/session/config/SecurityConfig.java:33`).
  `show-details: always` (`application.yml:60`) obejmuje m.in. stan połączenia z bazą — gdy RDS
  nie odpowiada, health zwraca 503 i ALB wyjmie task z ruchu.

**Listenery** (`alb/main.tf:39-89`) — logika sterowana zmienną `acm_certificate_arn`:

| `acm_certificate_arn` | Listener `HTTP:80` | Listener `HTTPS:443` |
|---|---|---|
| `""` (domyślnie w dev) | `forward` → target group | nie istnieje (`count = 0`) |
| ARN certyfikatu | `redirect` 301 → HTTPS | `forward`, polityka `ELBSecurityPolicy-TLS13-1-2-2021-06` |

To ładny przykład `dynamic` bloków: `for_each = cond ? [1] : []` generuje blok `redirect` albo
`forward`. Jest tylko akcja domyślna — brak reguł path-based, bo za ALB stoi jeden serwis.

> Uwaga: `ecs-sg` wpuszcza z ALB także port 8081 (`modules/network/main.tf:184-190`), choć nie istnieje
> target group dla notification-service. Reguła jest nieszkodliwa, ale martwa.

### 3.9 Okablowanie w `environments/dev/main.tf`

```hcl
# environments/dev/main.tf:81-90
module "alb" { public_subnet_ids = module.network.public_subnet_ids
               security_group_id = module.network.alb_sg_id
               acm_certificate_arn = var.acm_certificate_arn }

# environments/dev/main.tf:94-129
module "ecs" {
  subnet_ids        = module.network.private_subnet_ids
  security_group_id = module.network.ecs_sg_id
  charging_session_image = var.charging_session_image
  target_group_arn  = module.alb.charging_session_target_group_arn
  db_host = module.rds.host  ...  db_password_secret_arn = module.rds.db_password_secret_arn
  sns_topic_arn = module.messaging.sns_topic_arn ... cognito_jwks_uri = module.cognito.jwks_uri
}
```

Moduł `ecs` jest „zlewem” — dostaje outputy z `network`, `alb`, `rds`, `messaging`, `dynamodb`,
`cognito`. Terraform buduje z tych referencji graf zależności, więc ECS powstaje po RDS i ALB.
Zauważ, że moduł `ecr` **nie jest** połączony z `ecs` — obraz przychodzi jako zwykły string
`var.charging_session_image` (z CI).

### 3.10 Pipeline: od commita do działającego kontenera (`.github/workflows/deploy.yml`)

1. OIDC → rola `AWS_DEPLOY_ROLE_ARN` (`deploy.yml:27-31`).
2. `amazon-ecr-login` → `docker login` do ECR (`deploy.yml:34-36`).
3. `docker build` + `docker push` z tagiem `${{ github.sha }}` (`deploy.yml:46-60`).
4. `terraform apply -var charging_session_image=<registry>/...:<sha>` (`deploy.yml:93-99`).
5. (Patrz Uwaga w 3.7 — tu powinien być rollout serwisu.)

### 3.11 Droga requestu: internet → kontroler Springa

1. Klient wysyła `GET http://<alb-dns>/api/stations` (DNS ALB z outputu `alb_dns_name`).
2. DNS zwraca publiczne IP węzłów ALB w publicznych subnetach; `alb-sg` wpuszcza port 80.
3. Listener `HTTP:80` → akcja `forward` → target group `chargeflow-dev-css-tg`.
4. ALB wybiera zdrowy target (IP taska w prywatnym subnecie) i otwiera połączenie na `:8080`.
   `ecs-sg` przepuszcza je, bo źródłem jest `alb-sg` (reguła SG→SG, `network/main.tf:174-180`).
5. W tasku Fargate: Tomcat (embedded) na 8080 → filtry Spring Security (walidacja JWT z JWKS Cognito)
   → `DispatcherServlet` → np. `ChargingStationController`.
6. Odpowiedź wraca tą samą drogą; ALB dodaje nagłówki `X-Forwarded-For/Proto`.

---

## 4. Diagram

```
                          Internet
                             │  HTTP :80  (HTTPS :443 tylko gdy acm_certificate_arn != "")
                             ▼
 ┌──────────────────────── VPC 10.0.0.0/16 ─────────────────────────────────────────┐
 │  Public subnets (10.0.1.0/24, 10.0.2.0/24)                                         │
 │   ┌──────────────────────────────┐        ┌───────────────┐                        │
 │   │ ALB  chargeflow-dev-alb      │        │ NAT Gateway   │──► Internet (ECR,      │
 │   │ sg: alb-sg (80,443 ← 0/0)    │        └───────▲───────┘    Cognito JWKS, SNS…) │
 │   │ Listener HTTP:80 → forward   │                │                                │
 │   └──────────────┬───────────────┘                │ egress                         │
 │                  │ target group css-tg (ip, :8080, /actuator/health)              │
 │  Private subnets │ (10.0.10.0/24, 10.0.20.0/24)   │                                │
 │   ┌──────────────▼───────────────┐   ┌────────────┴────────────────┐              │
 │   │ ECS Service CSS (Fargate)    │   │ ECS Service NS (Fargate)    │              │
 │   │ task: 0.25 vCPU / 512 MiB    │   │ task: 0.25 vCPU / 512 MiB   │              │
 │   │ container :8080  sg: ecs-sg  │   │ container :8081, brak ALB   │◄── SQS poll  │
 │   └──────┬───────────────────────┘   └─────────────────────────────┘              │
 │          │ :5432 (rds-sg ← ecs-sg)                                                 │
 │   ┌──────▼──────┐                                                                  │
 │   │ RDS Postgres│                                                                  │
 │   └─────────────┘                                                                  │
 └────────────────────────────────────────────────────────────────────────────────────┘

 Start taska (agent ECS, execution role):          Runtime (aplikacja, task role):
   ECR ──pull image──► task                           SNS Publish, SQS Receive/Delete,
   Secrets Manager ──DB_PASSWORD──► env               DynamoDB, SES
   stdout ──awslogs──► CloudWatch /chargeflow/dev/<svc>/ecs

 CI: git push → GitHub Actions → docker build → ECR (:<sha>) → terraform apply (nowa rewizja TD)
```

---

## 5. Lokalnie vs AWS (dev)

| Aspekt | Lokalnie | AWS dev |
|---|---|---|
| Uruchomienie | `./mvnw spring-boot:run` na hoście (README:133-136) | Fargate task z obrazu ECR |
| Obraz Dockera | nieużywany (docker-compose ma tylko Postgres + LocalStack) | multi-stage Dockerfile, budowany w CI |
| Konfiguracja | wartości domyślne z `application.yml` / profil `local` | env vars + `secrets` w task definition |
| Wejście HTTP | `localhost:8080` bezpośrednio | ALB:80 → target group → task:8080 |
| Credentiale AWS | `test/test` do LocalStack (`AWS_ENDPOINT_URL`) | task role (STS, rotowane automatycznie) |
| Logi | konsola IDE (JSON ECS) | CloudWatch Logs `/chargeflow/dev/<svc>/ecs` |
| Health | `curl localhost:8080/actuator/health` | ALB health check + (zepsuty) container health check |
| Restart po awarii | ręcznie | ECS service odtwarza task do `desired_count` |

---

## 6. Koszty i pułapki

**Koszty (eu-central-1, rzędy wielkości — sprawdź aktualny cennik):**

- **Fargate**: płacisz za vCPU‑godzinę i GB‑godzinę od pull’a obrazu do zatrzymania taska.
  2 serwisy × 0.25 vCPU / 0.5 GB × 24/7 to kilkanaście–kilkadziesiąt USD/mies. W czasie deployu
  (200%) chwilowo podwójnie.
- **ALB**: stała opłata godzinowa (~16–20 USD/mies.) + LCU — płacisz nawet przy zerowym ruchu.
- **NAT Gateway** (z modułu network, ale napędzany przez ECS): opłata godzinowa + za każdy GB.
  Każdy pull obrazu z ECR (~200–300 MB JRE + jar) idzie przez NAT. Brak VPC endpoints dla ECR/S3/logs.
- **Container Insights**: dodatkowe metryki niestandardowe CloudWatch — niewielki, ale stały koszt.
- **ECR**: storage ~0.10 USD/GB/mies.; lifecycle (10 obrazów) trzyma to w ryzach.
- **CloudWatch Logs**: ingest per GB; logi JSON + tracing sampling `1.0` potrafią rosnąć.

**Pułapki:**

1. **Brak `curl` w obrazie** a container health check używa `curl` — pętla restartów (sekcja 3.6).
2. **`ignore_changes = [task_definition]` bez `update-service`** — „deploy przeszedł”, a działa
   stary kod (sekcja 3.7).
3. **Architektura CPU**: task definition nie ustawia `runtime_platform`, więc Fargate uruchamia
   `X86_64`. Obraz zbudowany na Macu z Apple Silicon to `arm64` → `exec format error`.
   Budując lokalnie do ECR użyj `docker build --platform linux/amd64`. (CI na `ubuntu-latest` buduje amd64.)
4. **512 MiB dla JVM**: Java 25 domyślnie bierze 25% RAM kontenera na heap (~128 MiB). Spring Boot +
   Hibernate + Flyway na 0.25 vCPU startuje wolno; ALB zaczyna health checki od razu, a serwis nie ma
   `health_check_grace_period_seconds` — przy długim starcie ECS może ubić task, zanim się podniesie.
5. **Execution role vs task role** — brak `GetSecretValue` w *execution* role daje błąd startu
   `ResourceInitializationError`, a nie wyjątek w aplikacji. Brak uprawnienia w *task* role daje
   `AccessDeniedException` z AWS SDK w logach Springa.
6. **Rejestr placeholder** przy pierwszym `apply` — serwis nie wystartuje, dopóki nie podasz
   prawdziwego URI obrazu.
7. **HTTP tylko**: bez ACM certyfikatu JWT leci otwartym tekstem przez internet do ALB.

---

## 7. Ćwiczenia

Przygotowanie: `brew install awscli terraform` (ćwiczenia 1–3 są darmowe i lokalne).

**Ćwiczenie 1 — zbuduj obraz i znajdź bug health checka (darmowe)**

```bash
cd charging-session-service
docker build -t css:local .
docker images css:local                      # rozmiar obrazu runtime
docker run --rm --entrypoint sh css:local -c 'id; which curl || echo "brak curl"'
docker history css:local | head -15          # warstwy: zależności Mavena vs kod
```

Pytanie pomocnicze: który użytkownik uruchamia proces i dlaczego health check z task definition nie zadziała?

**Ćwiczenie 2 — uruchom kontener „jak w ECS” przeciwko lokalnemu Postgresowi (darmowe)**

```bash
docker compose up -d postgres                # z katalogu głównego repo
docker run --rm -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/chargeflow \
  -e DB_USERNAME=chargeflow -e DB_PASSWORD=chargeflow_local \
  -e AWS_REGION=eu-central-1 \
  css:local
curl -s localhost:8080/actuator/health | jq   # w drugim terminalu
```

Tak właśnie ECS przekazuje konfigurację: same zmienne środowiskowe, zero plików.

**Ćwiczenie 3 — przeczytaj wygenerowaną task definition bez konta AWS (darmowe)**

```bash
cd infrastructure/environments/dev
terraform init -backend=false
terraform validate
terraform graph | grep -E 'module.ecs.*(task_definition|service)' | head   # zależności ECS
grep -n -A12 'secrets\|environment = \[' ../../modules/ecs/main.tf             # źródło JSON-a
```

`terraform plan` wymaga credentiali AWS (provider odpytuje `aws_caller_identity`), więc bez konta
skończy się na walidacji. Z kontem: `terraform plan -var github_repo=me/chargeflow -out tf.plan &&
terraform show -json tf.plan | jq '.resource_changes[] | select(.type=="aws_ecs_task_definition") | .change.after.container_definitions | fromjson'`
— zobaczysz finalny JSON kontenera z `environment`, `secrets` i `logConfiguration`. `plan` niczego nie tworzy.

**Ćwiczenie 4 — zbuduj obraz na właściwą architekturę (darmowe)**

```bash
docker buildx build --platform linux/amd64 -t css:amd64 charging-session-service
docker inspect css:amd64 --format '{{.Architecture}}'
```

**Ćwiczenie 5 — (KOSZTUJE, tylko jeśli masz wdrożone dev) diagnoza serwisu**

```bash
aws ecs describe-services --cluster chargeflow-dev-cluster \
  --services chargeflow-dev-charging-session-service \
  --query 'services[0].{td:taskDefinition,running:runningCount,events:events[0:5].message}'
aws ecs list-task-definitions --family-prefix chargeflow-dev-charging-session-service
aws elbv2 describe-target-health --target-group-arn <arn z describe-target-groups>
aws logs tail /chargeflow/dev/charging-session-service/ecs --follow
```

Porównaj rewizję `taskDefinition` w serwisie z najnowszą z `list-task-definitions` — to test na bug z sekcji 3.7.

---

## 8. Pytania kontrolne

1. Czym różni się execution role od task role i która z nich potrzebuje `secretsmanager:GetSecretValue` w ChargeFlow?
<details><summary>Odpowiedź</summary>

Execution role używa agent ECS przy starcie taska (pull z ECR, logi do CloudWatch, pobranie sekretów
do env). Task role dostaje aplikacja w runtime (AWS SDK). `GetSecretValue` jest w execution role
(`modules/ecs/main.tf:88-100`), bo to agent ECS wstrzykuje `DB_PASSWORD` zanim JVM wystartuje —
aplikacja nigdy nie woła Secrets Managera.
</details>

2. Dlaczego target group ma `target_type = "ip"`, a nie `instance`?
<details><summary>Odpowiedź</summary>

Fargate z `network_mode = "awsvpc"` daje każdemu taskowi własny ENI i prywatny IP; nie ma instancji
EC2, którą można by zarejestrować. ECS service rejestruje w target group adres IP taska i port 8080.
</details>

3. Co się stanie po `git push` na `main`, jeśli zmienisz kod kontrolera — czy nowy kod zacznie obsługiwać ruch?
<details><summary>Odpowiedź</summary>

Obraz z tagiem SHA trafi do ECR, a `terraform apply` zarejestruje nową rewizję task definition.
Jednak `lifecycle { ignore_changes = [task_definition] }` w `aws_ecs_service` powoduje, że serwis nie
zostanie przełączony, a workflow nie wywołuje `aws ecs update-service`. Ruch dalej obsługuje stara rewizja.
</details>

4. Jak działa listener HTTP, gdy `acm_certificate_arn` jest pusty, a jak gdy jest ustawiony?
<details><summary>Odpowiedź</summary>

Pusty: listener `HTTP:80` robi `forward` do target group, listener HTTPS nie istnieje (`count = 0`).
Ustawiony: `HTTP:80` robi `redirect` 301 na HTTPS:443, a listener HTTPS (TLS 1.3/1.2) robi `forward`.
Przełącznik to bloki `dynamic` z `for_each = cond ? [1] : []`.
</details>

5. Dlaczego notification-service nie ma target group i czy to problem?
<details><summary>Odpowiedź</summary>

Notification-service to konsument SQS (pull), nie wystawia publicznego API — nie potrzebuje ALB.
Konsekwencja: jedyny health check to container health check w task definition, który (przez brak
`curl` w obrazie) nie działa poprawnie. Reguła `ecs-sg` dla portu 8081 z ALB jest martwa.
</details>

6. Jaki efekt mają `deployment_minimum_healthy_percent = 100` i `deployment_maximum_percent = 200` przy `desired_count = 1`?
<details><summary>Odpowiedź</summary>

ECS nie może zejść poniżej 1 działającego taska, a może mieć chwilowo 2. Najpierw startuje nowy task,
czeka aż przejdzie health checki (ALB), dopiero potem drenuje i zatrzymuje stary — zero downtime
kosztem chwilowo podwójnych zasobów Fargate.
</details>
