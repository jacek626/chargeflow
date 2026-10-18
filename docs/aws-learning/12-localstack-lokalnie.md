# 12 — Lokalne środowisko: Docker Compose + LocalStack

> Na podstawie `docker-compose.yml`, `.env.example`, `infrastructure/environments/local/`, `scripts/` oraz plików `application*.yml` obu serwisów. Ścieżki są względne do katalogu głównego repozytorium.

---

## 1. Po co to jest

Testowanie na prawdziwym AWS jest wolne (deploy), kosztowne (NAT, RDS, ALB działają 24/7) i ryzykowne (pomyłka w `apply`). **LocalStack** to kontener Dockera, który udaje API AWS na jednym porcie, `4566`. To jak **H2 albo Testcontainers dla AWS**: kod Javy używa prawdziwego AWS SDK, wywołuje `sqs.sendMessage(...)`, a po drugiej stronie odpowiada emulator.

Kluczowa idea: **ten sam kod, inna konfiguracja**. Aplikacja nie wie, czy rozmawia z LocalStackiem, czy z AWS. Różnią się tylko:
- adres endpointu (`http://localhost:4566` vs domyślny AWS),
- poświadczenia (`test/test` vs rola IAM),
- nazwy zasobów (`chargeflow-local-*` vs `chargeflow-dev-*`).

---

## 2. Kluczowe pojęcia AWS

- **Endpoint** — URL API usługi, np. `https://sqs.eu-central-1.amazonaws.com`. SDK wylicza go z regionu. **Endpoint override** każe SDK wysłać żądanie gdzie indziej.
- **`AWS_ENDPOINT_URL`** — standardowa zmienna środowiskowa nadpisująca endpoint dla *wszystkich* usług. Rozpoznają ją nowsze wersje AWS CLI v2, AWS SDK oraz provider Terraform AWS.
- **Default credentials provider chain** — kolejność, w jakiej SDK szuka poświadczeń: zmienne env → `~/.aws` → rola kontenera ECS/instancji. Lokalnie trafia na `AWS_ACCESS_KEY_ID=test`, w ECS na rolę zadania (task role).
- **Region** — LocalStack też go rozróżnia: zasób utworzony w `eu-central-1` nie będzie widoczny w `us-east-1`.
- **LocalStack Community vs Pro** — część usług (np. Cognito) jest dostępna tylko w płatnej wersji. Wersja community obsługuje m.in. SQS, SNS, DynamoDB, S3, CloudWatch Logs, SES (emulowane: e-maile nie wychodzą naprawdę).
- **Path-style S3** — `http://localhost:4566/bucket/key` zamiast `http://bucket.s3.amazonaws.com/key`. Lokalnie nie ma DNS dla subdomen bucketów, więc trzeba wymusić path-style.

---

## 3. Jak to działa w ChargeFlow

### 3.1 `docker-compose.yml` — dwa kontenery

Komentarz w nagłówku (linie 1-15) mówi wprost: Compose uruchamia **tylko infrastrukturę**. Serwisy Spring i frontend uruchamiasz na hoście.

**postgres** (linie 19-36)
```yaml
image: postgres:18.6
environment:
  POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-chargeflow_local}
ports: ["5432:5432"]
volumes: [chargeflow-postgres-data:/var/lib/postgresql]
```
- Zastępuje RDS. Dane przeżywają restart dzięki nazwanemu wolumenowi (`docker compose down -v` je kasuje).
- `${VAR:-default}` to składnia Compose: wartość z `.env`, a jeśli jej nie ma, to domyślna.
- `healthcheck` z `pg_isready` (linie 30-35) pozwala stwierdzić, że baza faktycznie przyjmuje połączenia.

**localstack** (linie 38-53)
```yaml
image: localstack/localstack:4.14.0
ports: ["4566:4566"]
environment:
  - DEFAULT_REGION=${AWS_REGION:-eu-central-1}
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:4566/_localstack/health"]
```
- Jeden port 4566 = wszystkie emulowane usługi („edge port”).
- Wersja przypięta (`4.14.0`), nie `latest`. Zachowanie emulatora zmienia się między wersjami.
- Brak zmiennej `SERVICES`, więc LocalStack uruchamia usługi leniwie, przy pierwszym żądaniu.
- **Brak persystencji:** nie ma wolumenu ani `PERSISTENCE=1`. Po restarcie kontenera kolejki, topic i tabela DynamoDB **znikają**. Lokalny state Terraform nadal je pamięta (patrz pułapki).

### 3.2 `.env.example`

```
POSTGRES_DB=chargeflow / POSTGRES_USER=chargeflow / POSTGRES_PASSWORD=chargeflow_local
DB_URL=jdbc:postgresql://localhost:5432/chargeflow          # linia 16
AWS_REGION=eu-central-1
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
AWS_ENDPOINT_URL=http://localhost:4566                     # linia 24
```

Kopiujesz go do `.env` (gitignorowany: `.env` + `.env.*`, z wyjątkiem `!.env.example`). Ważne rozróżnienie:
- **Docker Compose czyta `.env` automatycznie** do podstawiania `${...}` w `docker-compose.yml`.
- **Spring Boot NIE czyta `.env`.** Zmienne `DB_URL`, `AWS_ENDPOINT_URL` itd. muszą być wyeksportowane w shellu (lub w konfiguracji uruchomieniowej IntelliJ). Dlatego `start-local.sh` wypisuje komendy `export ...` i sam je ustawia przy `--services`.

`test`/`test` to wartości umowne. LocalStack akceptuje dowolne poświadczenia, ale SDK wymaga, żeby *jakieś* istniały.

### 3.3 Terraform `environments/local` — provider skierowany na LocalStack

```hcl
# infrastructure/environments/local/providers.tf:5-26
provider "aws" {
  region = var.aws_region
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true
  s3_use_path_style           = true
  default_tags { tags = local.common_tags }
}
```

| Ustawienie | Po co |
|---|---|
| `skip_credentials_validation` | Nie wołaj STS `GetCallerIdentity`, żeby sprawdzić klucze `test/test` |
| `skip_metadata_api_check` | Nie odpytuj EC2 Instance Metadata (169.254.169.254), na laptopie go nie ma |
| `skip_requesting_account_id` | Nie ustalaj ID konta. LocalStack używa umownego `000000000000` |
| `s3_use_path_style` | Path-style URL-e S3 (patrz pojęcia) |

**Gdzie jest endpoint?** W pliku **nie ma** bloku `endpoints { sqs = "http://localhost:4566" ... }`. Endpoint przychodzi wyłącznie ze zmiennej środowiskowej `AWS_ENDPOINT_URL` (komentarz w liniach 4-9). To zwięzłe rozwiązanie, ale **niebezpieczne**: jeśli zapomnisz `export AWS_ENDPOINT_URL=...`, a w shellu masz aktywny `AWS_PROFILE` z prawdziwym kontem, `terraform apply` w `local` stworzy zasoby `chargeflow-local-*` **w prawdziwym AWS**. Bez profilu zakończy się błędem poświadczeń.

`infrastructure/environments/local/main.tf` tworzy przez 4 moduły:
- `messaging`: SNS topic `chargeflow-local-domain-events`, kolejka `chargeflow-local-notifications`, DLQ `...-notifications-dlq`, polityka kolejki i subskrypcja SNS→SQS z `raw_message_delivery = true`,
- `dynamodb`: tabela `chargeflow-local-notifications` (klucz `eventId`, GSI `customerId-createdAt-index`),
- `ses`: tożsamość e-mail `noreply@chargeflow.pl` (`main.tf:26`, wpisane na sztywno),
- `monitoring`: log groupy CloudWatch `/chargeflow/local/...` z `log_retention_days = 7` (`main.tf:36`).

Brak `backend.tf`, więc state leży w `infrastructure/environments/local/terraform.tfstate` (gitignorowany). Outputy (`environments/local/outputs.tf`) to ARN/URL-e messaging i nazwa tabeli. **Nie ma outputów Cognito.**

### 3.4 Skrypty

**`scripts/start-local.sh`** to główny punkt wejścia:
1. `--down` → `docker compose down` (linie 28-33).
2. Sprawdza `docker`, `terraform`, `java`, `node` i Javę 25+ (linie 37-45).
3. Kopiuje `.env.example` → `.env`, jeśli brak, i robi `docker compose up -d` (linie 50-52).
4. Czeka na health LocalStacka (`/_localstack/health`, linie 55-61) i Postgresa (`pg_isready`, linie 65-71).
5. Eksportuje `test/test` + `AWS_ENDPOINT_URL` i robi **`terraform init -upgrade` + `terraform apply -auto-approve`** w `environments/local` (linie 79-85).
6. Czyta `terraform output -raw sns_topic_arn / sqs_queue_url / dynamodb_notifications_table_name` (linie 90-92) i wypisuje gotowe `export ...` dla obu serwisów (linie 110-132).
7. Z `--services` uruchamia oba serwisy `./mvnw spring-boot:run` w tle, a PID-y zapisuje w `/tmp/chargeflow-*.pid` (linie 154-195).

Mostem między Terraform a aplikacją jest tu `terraform output`. ARN topicu nie jest wpisany na sztywno, tylko odczytany z state.

> Uwaga (błąd na macOS): linia 43 używa `grep -oP` (Perl regex). Domyślny `grep` w macOS (BSD) nie obsługuje `-P`. Przy `set -euo pipefail` skrypt zakończy się na sprawdzaniu wersji Javy, chyba że masz GNU grep jako `grep` (np. `brew install grep` + PATH).

> Uwaga: `terraform init -upgrade` może podnieść providery w granicach `~> 6.64.0` i zmodyfikować commitowany `.terraform.lock.hcl`.

**`scripts/create-local-user.sh`** i **`scripts/get-local-token.sh`** tworzą użytkownika w Cognito na LocalStacku i pobierają token (`initiate-auth` z `USER_PASSWORD_AUTH`).

> Uwaga (niespójność): oba skrypty czytają `terraform output -raw cognito_user_pool_id` (i `cognito_client_id`) z `environments/local`, ale **środowisko local nie wywołuje modułu `cognito` i nie ma takich outputów**. Skrypty zakończą się błędem. Cognito w LocalStacku wymaga ponadto wersji Pro. Aktualnym sposobem jest to, co wypisuje `start-local.sh` (linie 106-108): profil Spring `local` z własnym JWT: `POST http://localhost:8080/dev/token` i `GET /dev/jwks.json` (`LocalDevController`, `@Profile("local")`). Skrypty Cognito wyglądają na pozostałość wcześniejszego podejścia.

### 3.5 Konfiguracja aplikacji: LocalStack vs prawdziwe AWS

Oba serwisy mają ten sam wzorzec w `application.yml`:

```yaml
# charging-session-service/src/main/resources/application.yml:39-44
chargeflow:
  aws:
    region: ${AWS_REGION:eu-central-1}
    endpoint-url: ${AWS_ENDPOINT_URL:}      # pusty domyślnie!
  sns:
    enabled: ${SNS_ENABLED:false}
    topic-arn: ${SNS_TOPIC_ARN:}
```

i w kodzie Javy:

```java
// charging-session-service/src/main/java/pl/chargeflow/session/outbox/SnsConfig.java:23-28
SnsClientBuilder builder = SnsClient.builder()
        .region(Region.of(region))
        .credentialsProvider(DefaultCredentialsProvider.create());
if (StringUtils.hasText(endpointUrl)) {
    builder.endpointOverride(URI.create(endpointUrl));
}
```

- **Lokalnie** `AWS_ENDPOINT_URL=http://localhost:4566` → `endpointOverride` → LocalStack; poświadczenia `test/test` z env.
- **W AWS (ECS)** task definition **nie ustawia** `AWS_ENDPOINT_URL` (`infrastructure/modules/ecs/main.tf:214-220` i `222-231`), więc wartość jest pusta, nie ma override i SDK używa prawdziwego endpointu. Poświadczenia pochodzą z roli zadania ECS przez ten sam `DefaultCredentialsProvider`.

Ten sam wzorzec mają `SqsConfig`, `SnsConfig`, `SesConfig` i `DynamoDbConfig` w `notification-service/src/main/java/pl/chargeflow/notification/config/`.

**Feature flagi** `@ConditionalOnProperty(name = "chargeflow.sns.enabled", havingValue = "true")`: domyślnie SNS/SQS/SES są **wyłączone** (`false`). Serwis uruchomisz bez LocalStacka, a integracje włączasz świadomie. Lokalnie `start-local.sh` włącza `SNS_ENABLED=true` i `SQS_ENABLED=true`, ale **`SES_ENABLED=false`** (linia 132). W ECS SES jest włączony (`ecs/main.tf:282`). `DynamoDbConfig` nie ma flagi, więc klient DynamoDB powstaje zawsze.

Domyślna nazwa tabeli w notification-service: `${DYNAMODB_NOTIFICATIONS_TABLE:chargeflow-local-notifications}` (`notification-service/src/main/resources/application.yml:16`) to dokładnie to, co tworzy Terraform (`name_prefix` = `chargeflow-local` + `-notifications`).

**JWT / Cognito**
- `application.yml:34`: `jwk-set-uri: ${COGNITO_JWKS_URI:http://localhost:4566/eu-central-1_local/.well-known/jwks.json}`. Domyślnie wskazuje na (nieistniejący lokalnie) Cognito w LocalStacku.
- `application-local.yml:9` nadpisuje to na `http://localhost:8080/dev/jwks.json`, czyli JWKS z samego serwisu.
- W ECS: `COGNITO_JWKS_URI = module.cognito.jwks_uri` (`ecs/main.tf:220`).

**Hasło DB:** `application.yml:8` ma domyślne `${DB_PASSWORD:chargeflow}`, a Compose używa `chargeflow_local`. Pokrywa to `application-local.yml:3` (`password: chargeflow_local`) albo zmienna `DB_PASSWORD`. Bez profilu `local` i bez `DB_PASSWORD` połączenie z lokalnym Postgresem się nie uda. W ECS hasło przychodzi z Secrets Manager (`valueFrom`, `ecs/main.tf:226`).

---

## 4. Diagram

```
 Twój laptop
 ┌───────────────────────────────────────────────────────────────────────┐
 │  shell: export AWS_ENDPOINT_URL=http://localhost:4566, test/test      │
 │                                                                       │
 │  scripts/start-local.sh                                               │
 │     │ 1. docker compose up -d                                         │
 │     │ 2. terraform apply (environments/local) ───────────┐            │
 │     │ 3. terraform output ──► export SNS_TOPIC_ARN ...   │            │
 │     ▼                                                    ▼            │
 │  ┌──────────────────────┐   HTTP :4566   ┌──────────────────────────┐ │
 │  │ charging-session-svc │ ─SNS Publish──►│ LocalStack 4.14.0        │ │
 │  │ :8080 profil local   │                │  SNS chargeflow-local-   │ │
 │  │  /dev/token,/dev/jwks│                │      domain-events       │ │
 │  └─────────┬────────────┘                │      │ raw delivery      │ │
 │            │ JDBC :5432                  │      ▼                   │ │
 │  ┌─────────▼────────────┐                │  SQS ...-notifications   │ │
 │  │ postgres:18.6        │                │      └─► DLQ             │ │
 │  │ volume: chargeflow-  │                │  DynamoDB ...-notific.   │ │
 │  │   postgres-data      │                │  SES (emulowany)         │ │
 │  └──────────────────────┘                │  CW Logs /chargeflow/... │ │
 │  ┌──────────────────────┐   poll SQS     └────────▲─────────────────┘ │
 │  │ notification-service │ ───────────────────────┘                    │
 │  │ :8081 SES_ENABLED=false, PutItem DynamoDB                          │
 │  └──────────────────────┘                                             │
 │  Angular  ng serve --port 4201 (skipAuth=true)                        │
 └───────────────────────────────────────────────────────────────────────┘

 W AWS (dev): te same JAR-y w ECS Fargate, AWS_ENDPOINT_URL nieustawione
 → SDK łączy się z sns/sqs/dynamodb.eu-central-1.amazonaws.com, task role.
```

---

## 5. Lokalnie vs AWS (dev)

| Element | Lokalnie | AWS dev |
|---|---|---|
| Baza | `postgres:18.6` w Dockerze | RDS PostgreSQL (`postgres_version = "16"` w module rds) |
| SNS/SQS/DynamoDB | LocalStack `chargeflow-local-*` | prawdziwe usługi `chargeflow-dev-*` |
| SES | moduł tworzy identity, ale serwis ma `SES_ENABLED=false` | `SES_ENABLED=true`, wymagana weryfikacja nadawcy |
| Auth | profil `local`, `/dev/token`, `/dev/jwks.json` | Cognito, `COGNITO_JWKS_URI` |
| Poświadczenia | `test/test` z env | ECS task role |
| Endpoint | `AWS_ENDPOINT_URL` | domyślny (puste) |
| Hasło DB | `chargeflow_local` jawnie | Secrets Manager |
| Terraform state | lokalny plik | S3 |
| Sieć | brak VPC | VPC, prywatne subnety, SG |

> Uwaga: wersje Postgresa się różnią: 18.6 lokalnie, a w module RDS domyślnie `"16"` (`infrastructure/modules/rds/variables.tf:62-65`). Migracje Flyway działające na 18 mogą teoretycznie użyć składni niedostępnej w 16. Warto to zsynchronizować.

---

## 6. Koszty i pułapki

**Koszty:** całe środowisko lokalne jest **darmowe** (LocalStack community + Docker). Pieniądze możesz wydać tylko przez pomyłkę, czyli przez `apply` z poświadczeniami do prawdziwego konta i bez `AWS_ENDPOINT_URL`.

**Pułapki**
1. **Brak `AWS_ENDPOINT_URL` = prawdziwy AWS.** Dotyczy Terraform (`environments/local` nie ma bloku `endpoints`), AWS CLI i aplikacji. Zawsze sprawdź `env | grep AWS_` przed `apply`.
2. **Rozjazd state ↔ LocalStack po restarcie.** LocalStack bez persystencji traci zasoby, a `terraform.tfstate` wciąż je pamięta. `terraform apply` zwykle sam to wykryje przy refresh i odtworzy zasoby. Jeśli coś się zatnie: `rm terraform.tfstate*` w `environments/local` (tylko tam!) i ponowny `apply`.
3. **Nowe ARN/URL po odtworzeniu.** URL kolejki może być ten sam, ale zawsze bierz go z `terraform output`, a nie z pamięci.
4. **`.env` ≠ zmienne dla Springa.** Uruchamiając serwis z IntelliJ, ustaw zmienne w Run Configuration albo włącz profil `local`.
5. **Emulator ≠ AWS.** Uprawnienia IAM, polityki kolejki SQS i limity nie są domyślnie egzekwowane przez LocalStack. Kod działający lokalnie może dostać `AccessDenied` w dev, jeśli rola ECS nie ma uprawnień.
6. **Skrypty Cognito i `grep -oP`** — patrz 3.4, na macOS nie zadziałają bez poprawek.
7. **`docker compose down -v`** kasuje wolumen Postgresa (komentarz w linii 13).

---

## 7. Ćwiczenia

Instalacja: `brew install awscli terraform` (oraz `jq`, jeśli chcesz używać skryptów).

**Ćwiczenie 1 — uruchom infrastrukturę i sprawdź health (darmowe)**
```bash
cp -n .env.example .env
docker compose up -d
docker compose ps
curl -s http://localhost:4566/_localstack/health | jq .services
```
Które usługi mają status `available`, a które `running`? (Te drugie zostały już użyte.)

**Ćwiczenie 2 — Terraform local krok po kroku (darmowe)**
```bash
cd infrastructure/environments/local
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=eu-central-1 AWS_ENDPOINT_URL=http://localhost:4566
terraform init && terraform apply -var="environment=local"
terraform output
aws sns list-topics
aws sqs list-queues
aws dynamodb describe-table --table-name chargeflow-local-notifications --query 'Table.GlobalSecondaryIndexes[].IndexName'
```

**Ćwiczenie 3 — przepływ SNS → SQS ręcznie (darmowe)**
```bash
TOPIC=$(terraform output -raw sns_topic_arn); QUEUE=$(terraform output -raw sqs_queue_url)
aws sns publish --topic-arn "$TOPIC" --message '{"eventId":"test-1","type":"Demo"}'
aws sqs receive-message --queue-url "$QUEUE" --wait-time-seconds 5
```
Dzięki `raw_message_delivery = true` w body jest dokładnie Twój JSON, bez „koperty” SNS. Usuń to ustawienie w module (lokalnie, bez commitu), zrób `apply` i porównaj.

**Ćwiczenie 4 — symulacja utraty state'u LocalStacka (darmowe)**
```bash
docker compose restart localstack
aws sqs list-queues                       # pusto
terraform plan -var="environment=local"   # co Terraform chce odtworzyć?
```

**Ćwiczenie 5 — „ten sam kod, inny endpoint” (darmowe)**
Uruchom notification-service z `SQS_ENABLED=true`, wyślij wiadomość jak w ćw. 3, sprawdź wpis w DynamoDB:
```bash
aws dynamodb scan --table-name chargeflow-local-notifications --max-items 5
```
Potem uruchom serwis **bez** `AWS_ENDPOINT_URL` (i bez prawdziwych poświadczeń). Zobacz, że SDK próbuje łączyć się z `amazonaws.com` i zgłasza błąd poświadczeń. Dokładnie tak zachowuje się w ECS, tylko tam poświadczenia są.

---

## 8. Pytania kontrolne

**1. Skąd provider Terraform w `environments/local` wie, że ma gadać z `localhost:4566`?**
<details><summary>Odpowiedź</summary>
Ze zmiennej środowiskowej <code>AWS_ENDPOINT_URL</code>. W <code>providers.tf</code> nie ma bloku <code>endpoints</code>. Bez tej zmiennej Terraform celuje w prawdziwe AWS.
</details>

**2. Dlaczego ten sam JAR działa z LocalStackiem i z AWS bez przebudowy?**
<details><summary>Odpowiedź</summary>
Bo <code>chargeflow.aws.endpoint-url</code> jest pusty domyślnie i tylko gdy ma wartość, kod woła <code>endpointOverride</code>. Poświadczenia pobiera <code>DefaultCredentialsProvider</code>: lokalnie z env (<code>test/test</code>), w ECS z roli zadania. ECS task definition nie ustawia <code>AWS_ENDPOINT_URL</code>.
</details>

**3. Czy Spring Boot wczytuje plik `.env`?**
<details><summary>Odpowiedź</summary>
Nie. <code>.env</code> czyta automatycznie Docker Compose (do podstawiania <code>${...}</code>). Dla Springa zmienne muszą być wyeksportowane w shellu lub w konfiguracji IDE; robi to <code>start-local.sh --services</code>.
</details>

**4. Do czego służą `skip_credentials_validation` i `skip_requesting_account_id`?**
<details><summary>Odpowiedź</summary>
Wyłączają wywołania STS/IAM, którymi provider normalnie weryfikuje klucze i ustala ID konta. Z kluczami <code>test/test</code> i emulatorem byłyby zbędne lub zawodne.
</details>

**5. Dlaczego `./scripts/get-local-token.sh` obecnie nie zadziała i czego użyć zamiast niego?**
<details><summary>Odpowiedź</summary>
Czyta <code>terraform output cognito_user_pool_id</code>, a <code>environments/local</code> nie tworzy Cognito i nie ma takiego outputu (Cognito w LocalStacku wymaga też Pro). Zamiast tego: profil Spring <code>local</code> i <code>curl -X POST http://localhost:8080/dev/token -d 'role=CUSTOMER'</code>.
</details>

**6. Co się dzieje z kolejkami SQS po `docker compose restart localstack` i jak to naprawić?**
<details><summary>Odpowiedź</summary>
Znikają, bo LocalStack nie ma skonfigurowanej persystencji. State Terraform wciąż je zawiera; <code>terraform apply</code> wykryje brak przy refresh i odtworzy zasoby (lub uruchom ponownie <code>start-local.sh</code>).
</details>
