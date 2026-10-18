# 10 — IAM + CI/CD (GitHub Actions, OIDC, STS)

## 1. Po co to jest

W Springu masz Spring Security: **kto** (uwierzytelnienie) i **co mu wolno** (autoryzacja).
IAM robi to samo, ale dla wywołań API AWS-a. Każde `sns:Publish`, `ecr:PutImage`,
`s3:PutObject` to żądanie HTTP podpisane kluczem, które AWS sprawdza pod kątem polityk.

Analogia: **rola IAM to identyfikator gościa w biurowcu**. Nie jest przypisany do konkretnej osoby.
Recepcja (STS) wydaje go na kilka godzin temu, kto pokaże właściwy dokument (trust policy),
a identyfikator otwiera tylko określone drzwi (permission policy). Gdy wygaśnie — trzeba
wrócić na recepcję. Użytkownik IAM z kluczem dostępu to natomiast **dorobiony klucz do drzwi**:
działa latami, można go zgubić, wkleić do repozytorium albo wynieść z firmy.

ChargeFlow nie ma ani jednego użytkownika IAM ani statycznego klucza. Wszystko działa na rolach:
GitHub Actions dostaje rolę przez OIDC, kontenery ECS dostają role przez `ecs-tasks.amazonaws.com`.

## 2. Kluczowe pojęcia AWS

| Pojęcie | Co to jest |
|---|---|
| **Principal** | Kto wykonuje żądanie: użytkownik IAM, rola (dokładnie: sesja roli), usługa AWS (`sns.amazonaws.com`), tożsamość federacyjna (GitHub). |
| **IAM User** | Stała tożsamość z hasłem i/lub *access keys* (`AKIA...`). Długożyjące poświadczenia — dziś unikane. |
| **IAM Role** | Tożsamość bez własnych poświadczeń. Ktoś ją „przyjmuje” (*assume*) i dostaje **tymczasowe** klucze (`ASIA...` + session token). |
| **Identity-based policy** | Polityka podpięta do roli/użytkownika: „ta rola może `sns:Publish` na temacie X”. Managed (`AdministratorAccess`) albo inline (`aws_iam_role_policy`). |
| **Resource-based policy** | Polityka podpięta do zasobu: bucket policy S3, queue policy SQS. Ma pole `Principal` — mówi, *kto z zewnątrz* może dotknąć zasobu. |
| **Trust policy** | Szczególna resource-based policy roli (`assume_role_policy`): kto może tę rolę przyjąć i pod jakimi warunkami. |
| **Statement** | `Effect` (Allow/Deny) + `Action` + `Resource` (+ opcjonalnie `Condition`, `Principal`). |
| **Ewaluacja** | Domyślnie wszystko jest **zabronione**. Allow otwiera, jawny Deny zawsze wygrywa nad Allow. |
| **Least privilege** | Daj tylko te akcje, na tych zasobach, które są naprawdę potrzebne. |
| **STS `AssumeRole`** | Principal AWS (np. usługa ECS) prosi o tymczasowe klucze roli. |
| **STS `AssumeRoleWithWebIdentity`** | To samo, ale dowodem tożsamości jest token JWT od zewnętrznego dostawcy OIDC (GitHub, Google, Cognito) — bez kluczy AWS po stronie wołającego. |
| **OIDC provider (IAM)** | Wpis w IAM mówiący „ufam tokenom wystawionym przez `https://token.actions.githubusercontent.com`”. |
| **Claims `aud` / `sub`** | Pola w JWT GitHuba: `aud` = dla kogo token (`sts.amazonaws.com`), `sub` = skąd pochodzi (`repo:org/repo:ref:refs/heads/main`, `repo:org/repo:environment:dev`, `repo:org/repo:pull_request`). |

Dla programisty Javy: polityka IAM ≈ `@PreAuthorize`, tylko że deklarowana w JSON-ie poza
kodem, a `Condition` to coś jak `hasRole(...) and #repo == 'x'`.

## 3. Jak to działa w ChargeFlow

### 3.1. GitHub OIDC provider i rola deploy — `infrastructure/modules/iam-github`

Rejestracja GitHuba jako zaufanego dostawcy tożsamości:

```hcl
# infrastructure/modules/iam-github/main.tf:4
resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
```

- `client_id_list` — dopuszczalna wartość claimu `aud`.
- `thumbprint_list` — odcisk certyfikatu GitHuba. AWS od 2023 r. weryfikuje GitHuba przez
  własną listę zaufanych CA, więc ta wartość jest dziś praktycznie ignorowana, ale atrybut bywa
  wymagany przez provider Terraform.

Trust policy roli deploy (`infrastructure/modules/iam-github/main.tf:18-37`):

```hcl
Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
Action    = "sts:AssumeRoleWithWebIdentity"
Condition = {
  StringEquals = { "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com" }
  StringLike   = { "token.actions.githubusercontent.com:sub" = "repo:${var.github_repo}:*" }
}
```

Czytamy to tak: „Rolę `chargeflow-dev-github-deploy` może przyjąć każdy, kto pokaże token
z GitHuba, wystawiony dla STS, pochodzący z repozytorium `var.github_repo` — **z dowolnej gałęzi,
dowolnego PR-a, dowolnego environmentu** (bo `:*`)”.

Uprawnienia (`infrastructure/modules/iam-github/main.tf:42-49`):

```hcl
# AdministratorAccess for DEV — appropriate for an IaC deployment role in non-production.
policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
```

Uwaga: w połączeniu z `sub = repo:...:*` oznacza to, że **każdy workflow uruchomiony w repo
z dowolnego brancha** (np. ktoś z prawem push tworzy gałąź `test` z własnym workflow) dostaje
pełnego admina na koncie. Workflow `deploy.yml` używa `environment: dev`
(`.github/workflows/deploy.yml:21`), więc GitHub wystawi token z `sub = repo:ORG/REPO:environment:dev`.
Ciaśniejszy warunek byłby więc `StringEquals` na dokładnie tę wartość — wtedy dodatkowo działają
reguły ochrony environmentu w GitHubie (np. wymagani recenzenci, tylko gałąź `main`).

Rola jest wystawiona jako output (`infrastructure/modules/iam-github/outputs.tf:2-5`,
`infrastructure/environments/dev/outputs.tf:5-8`) i według `README.md:180` jej ARN trafia do
sekretu GitHuba `AWS_DEPLOY_ROLE_ARN`.

Kura i jajko: rola, której używa pipeline, jest tworzona **przez ten sam** Terraform, który
pipeline uruchamia. Dlatego `README.md:187-202` każe pierwszy `terraform apply` zrobić ręcznie,
z lokalnymi poświadczeniami administratora.

### 3.2. Wszystkie role i polityki w modułach — kto może co

`grep -rn "aws_iam" infrastructure/` znajduje role tylko w dwóch modułach: `iam-github` i `ecs`.
Do tego dochodzą dwie polityki zasobów (bucket policy, queue policy).

| Tożsamość / zasób | Kto może przyjąć / dotknąć | Co może | Plik |
|---|---|---|---|
| `chargeflow-dev-github-deploy` | GitHub Actions z repo (OIDC) | **Wszystko** (`AdministratorAccess`) | `modules/iam-github/main.tf:14-49` |
| `chargeflow-dev-ecs-execution-role` | `ecs-tasks.amazonaws.com` | Managed `AmazonECSTaskExecutionRolePolicy` (pull z ECR, zapis logów) + `secretsmanager:GetSecretValue` na sekrecie hasła DB | `modules/ecs/main.tf:65-100` |
| `chargeflow-dev-ecs-task-role` | `ecs-tasks.amazonaws.com` | SNS publish, SQS receive/delete, DynamoDB, SES, CloudWatch — szczegóły niżej | `modules/ecs/main.tf:108-186` |
| Bucket policy frontendu | `cloudfront.amazonaws.com`, tylko z naszej dystrybucji (`AWS:SourceArn`) | `s3:GetObject` | `modules/frontend/main.tf:43-64` |
| Queue policy SQS | `sns.amazonaws.com`, tylko z naszego tematu (`aws:SourceArn`) | `sqs:SendMessage` | `modules/messaging/main.tf:34-58` |

**Execution role vs task role** — klasyczne źródło pomyłek w ECS:

- **Execution role** używa *agent ECS*, zanim Twój kod wystartuje: ściąga obraz z ECR,
  pobiera `DB_PASSWORD` z Secrets Manager (`modules/ecs/main.tf:225-227`), tworzy strumień logów
  dla sterownika `awslogs`.
- **Task role** używa *Twoja aplikacja* — AWS SDK w Javie (`DefaultCredentialsProvider`,
  `charging-session-service/src/main/java/pl/chargeflow/session/outbox/SnsConfig.java:25`)
  automatycznie pobiera jej tymczasowe klucze z endpointu metadanych kontenera.

Polityka task role (`infrastructure/modules/ecs/main.tf:123-186`) w skrócie:

```hcl
{ Sid = "SNSPublish",  Action = ["sns:Publish"],             Resource = [var.sns_topic_arn] }
{ Sid = "SQSConsume",  Action = ["sqs:ReceiveMessage", ...], Resource = ["arn:aws:sqs:${region}:${account}:*"] }
{ Sid = "DynamoDB",    Action = ["dynamodb:PutItem", ...],   Resource = ["arn:aws:dynamodb:...:table/*"] }
{ Sid = "SESSend",     Action = ["ses:SendEmail", "ses:SendRawEmail"], Resource = ["*"] }
{ Sid = "CloudWatchMetrics", Action = ["cloudwatch:PutMetricData", "logs:CreateLogStream", "logs:PutLogEvents"], Resource = ["*"] }
```

Uwagi o least privilege (to są obserwacje z kodu, nie wymagania z dokumentacji):

1. **Jedna task role dla dwóch serwisów** (`modules/ecs/main.tf:199` i `:212`). Charging Session
   Service może więc czytać kolejki SQS, pisać do DynamoDB i wysyłać e-maile, a Notification Service
   może publikować na temat domenowy. Osobne role per serwis = mniejszy „blast radius”.
2. **SQS i DynamoDB na wildcardzie** (`:*`, `table/*`) — dostęp do *wszystkich* kolejek i tabel
   w regionie, a nie tylko `chargeflow-dev-notifications` i tabeli powiadomień. Moduł dostaje
   `sqs_queue_url`, a nie ARN, co pewnie skłoniło do wildcarda.
3. `logs:CreateLogStream` / `logs:PutLogEvents` w task role są zbędne — logi wysyła sterownik
   `awslogs` na execution role. `cloudwatch:PutMetricData` też nie jest używane: aplikacje nie mają
   rejestru CloudWatch dla Micrometera (zob. plik 11).
4. `ses:*` na `Resource = "*"` — można zawęzić do ARN tożsamości SES (`modules/ses`).
5. Trust policy ról ECS nie ma warunku `aws:SourceAccount` (ochrona przed *confused deputy*) —
   w jednym koncie dev to niewielkie ryzyko, ale dobra praktyka.

Plus: obie **polityki zasobów** są wzorcowe — `Principal` to usługa AWS, a `Condition` z
`SourceArn` gwarantuje, że chodzi o *nasz* temat SNS / *naszą* dystrybucję CloudFront, nie
dowolny w AWS.

### 3.3. Workflow CI — `.github/workflows/ci.yml`

CI w ogóle **nie rozmawia z AWS** (brak `permissions: id-token`, brak `configure-aws-credentials`):

| Job | Co robi | Linie |
|---|---|---|
| `test-charging-session-service` | Java 25 Temurin, `./mvnw test -B` | `ci.yml:14-32` |
| `test-notification-service` | jw. | `ci.yml:37-55` |
| `build-frontend` | Node 20, `npm ci`, `ng build --configuration=production` | `ci.yml:60-81` |
| `validate-terraform` | `terraform fmt -check -recursive`, `init -backend=false`, `validate` | `ci.yml:86-108` |

`terraform init -backend=false` (`ci.yml:103`) pozwala walidować kod bez dostępu do bucketu stanu
— nie potrzeba żadnych poświadczeń.

### 3.4. Workflow Deploy — `.github/workflows/deploy.yml` krok po kroku

1. **Trigger**: push do `main` lub ręcznie (`deploy.yml:3-6`).
2. **`permissions: id-token: write`** (`deploy.yml:9-11`) — bez tego runner nie może poprosić
   GitHuba o token OIDC. `contents: read` wystarcza do checkoutu.
3. **`environment: dev`** (`deploy.yml:21`) — zmienia claim `sub` na `...:environment:dev`
   i daje dostęp do sekretów environmentu.
4. **`aws-actions/configure-aws-credentials@v4`** (`deploy.yml:27-31`) z `role-to-assume:
   secrets.AWS_DEPLOY_ROLE_ARN`. Pod spodem: pobiera JWT od GitHuba → woła
   `sts:AssumeRoleWithWebIdentity` → eksportuje `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
   `AWS_SESSION_TOKEN` do kolejnych kroków (domyślnie ważne 1 h).
5. **`aws-actions/amazon-ecr-login@v2`** (`deploy.yml:34-36`) — `ecr:GetAuthorizationToken`
   + `docker login`; output `registry` = `<konto>.dkr.ecr.eu-central-1.amazonaws.com`.
6. **Build & push** (`deploy.yml:46-60`) — `docker build` + `docker push` z tagiem
   `${{ github.sha }}` do repo `chargeflow-dev-<serwis>` (nazwy zgodne z
   `infrastructure/modules/ecr/main.tf:5-6`). URI obrazu ląduje w `$GITHUB_ENV`.
   Krok `setup-java` (`deploy.yml:39-44`) jest tu zbędny — Dockerfile buduje JAR we własnym
   etapie `eclipse-temurin:25-jdk`.
7. **Build frontendu** (`deploy.yml:63-76`).
8. **`terraform init`** z backendem S3 (`deploy.yml:85-91`) — `TF_STATE_BUCKET`, `TF_LOCK_TABLE`
   z sekretów (tworzone przez `infrastructure/bootstrap/main.tf`).
9. **`terraform apply -auto-approve`** (`deploy.yml:93-99`) z `-var` dla obu obrazów i
   `github_repo=${{ github.repository }}`.
10. **Outputy** (`deploy.yml:102-106`) — nazwa bucketu i ID dystrybucji CloudFront
    (`terraform_wrapper: false` w `deploy.yml:83`, by `terraform output -raw` zwracał czysty tekst).
11. **`aws s3 sync --delete`** + osobny `aws s3 cp index.html` z `no-cache`
    (`deploy.yml:109-118`) — hashowane bundle są cache'owane na rok, `index.html` nigdy.
12. **`aws cloudfront create-invalidation --paths "/*"`** (`deploy.yml:120-124`).

Sekrety i zmienne: `secrets.AWS_DEPLOY_ROLE_ARN`, `secrets.TF_STATE_BUCKET`,
`secrets.TF_LOCK_TABLE`; `env.AWS_REGION=eu-central-1`, `env.TF_DIR`; kontekst `github.sha`,
`github.repository`. Żadnych `AWS_ACCESS_KEY_ID` w sekretach — o to chodzi w OIDC.

**Uwagi — rzeczy, które w kodzie wyglądają na błędy:**

- **Nowy obraz nie trafi do działającego serwisu.** Nie ma kroku `aws ecs update-service`;
  deploy liczy na to, że Terraform zaktualizuje serwis. Ale `aws_ecs_service` ma
  `lifecycle { ignore_changes = [task_definition] }` (`infrastructure/modules/ecs/main.tf:347-349`
  i `:303-305`). Terraform utworzy nową rewizję task definition z nowym obrazem, a serwis
  zostanie na starej. Potrzebny jest albo krok `aws ecs update-service --task-definition ...
  --force-new-deployment`, albo usunięcie `ignore_changes`.
- **Brak zmiennej `environment`.** `infrastructure/environments/dev/variables.tf:11-21` nie ma
  wartości domyślnej, a `*.tfvars` są w `.gitignore`. `deploy.yml:96-99` jej nie przekazuje
  (ani przez `-var`, ani `TF_VAR_environment`), więc nieinteraktywny `apply` w CI powinien
  skończyć się błędem „No value for required variable”.
- **Deploy nie czeka na CI.** `deploy.yml` i `ci.yml` startują niezależnie na tym samym pushu;
  nie ma `needs` ani `workflow_run`, więc kod z czerwonymi testami i tak zostanie wdrożony.
- Kolejność: obrazy są pushowane **przed** `terraform apply`, który tworzy repozytoria ECR —
  na „gołym” koncie pierwszy run się wywali (stąd ręczny pierwszy apply z README).
- Brak `terraform plan` jako osobnego kroku/artefaktu — każdy push do `main` to od razu
  `apply -auto-approve` z uprawnieniami admina.

Uwaga dokumentacyjna: `ARCHITECTURE.md` mówi „No credentials in code or Terraform state files”,
ale hasło DB jest generowane przez `random_password` i zapisywane do sekretu w
`infrastructure/modules/rds/main.tf:22-24` — więc **jest w stanie Terraformu**. Dostęp do
bucketu stanu = dostęp do hasła. To kolejny powód, by rola z dostępem do stanu była dobrze strzeżona.

## 4. Diagram

```
 GitHub Actions runner (deploy.yml, environment: dev)
   │ 1. "daj mi token OIDC" (permissions: id-token: write)
   ▼
 token.actions.githubusercontent.com
   │ 2. JWT { iss, aud: sts.amazonaws.com,
   │          sub: repo:ORG/chargeflow:environment:dev }
   ▼
 AWS STS  ── 3. AssumeRoleWithWebIdentity(RoleArn, JWT)
   │        sprawdza: OIDC provider zarejestrowany? aud == sts? sub LIKE repo:ORG/chargeflow:* ?
   │ 4. tymczasowe klucze (ASIA..., session token, ~1h)
   ▼
 rola chargeflow-dev-github-deploy  [AdministratorAccess]
   │
   ├──► ECR       docker login + push :<sha>
   ├──► S3 state  terraform init/apply (+ DynamoDB lock)
   │      └──► tworzy/aktualizuje: VPC, ECS task def, RDS, SNS/SQS, IAM ...
   │             └─X─► ECS service (ignore_changes = [task_definition] → bez rolloutu!)
   ├──► S3        aws s3 sync frontend/dist → bucket
   └──► CloudFront create-invalidation /*

 W runtime (AWS):
 ECS agent ──AssumeRole──► ecs-execution-role ──► ECR pull, Secrets Manager (DB_PASSWORD), awslogs
 Kontener  ──AssumeRole──► ecs-task-role      ──► SNS, SQS, DynamoDB, SES (wspólna dla obu serwisów)
 SNS       ──(queue policy, SourceArn)───────────► SQS
 CloudFront──(bucket policy, SourceArn)──────────► S3 frontend
```

## 5. Lokalnie vs AWS (dev)

| | Lokalnie | AWS dev |
|---|---|---|
| Poświadczenia aplikacji | `AWS_ACCESS_KEY_ID=test` / `test` (`scripts/start-local.sh:79-80`) | Task role, klucze tymczasowe z metadanych kontenera |
| Egzekwowanie IAM | LocalStack (community) domyślnie **nie sprawdza** polityk — wszystko przechodzi | Każde wywołanie sprawdzane |
| Hasło DB | `chargeflow_local` w `application-local.yml:3` / `docker-compose.yml` | Secrets Manager → execution role → zmienna `DB_PASSWORD` |
| Terraform | `environments/local`, brak modułu `iam-github` | `environments/dev` tworzy OIDC provider + rolę deploy |
| CI/CD | brak | `ci.yml` (bez AWS), `deploy.yml` (OIDC) |

Wniosek: błędy uprawnień zobaczysz **dopiero na AWS**. Lokalne testy nie złapią np. braku
`dynamodb:Query` w polityce.

## 6. Koszty i pułapki

- **IAM i STS są darmowe.** Kosztuje to, co rola pozwala zrobić — `AdministratorAccess` + skradziony
  workflow = koparka kryptowalut na Twoim rachunku.
- **Wildcard w `sub`** (`repo:org/repo:*`) — najczęstszy błąd w konfiguracjach OIDC. Jeszcze gorszy
  jest `repo:org/*` albo brak warunku `sub` (wtedy *dowolne* repo na GitHubie może przyjąć rolę!).
- **`Resource = "*"` / `table/*`** — działa od razu, więc nikt tego nie poprawia. Zawężaj od początku.
- **Wspólna rola dla wielu serwisów** — wygodne, ale łamie least privilege.
- **Execution role ≠ task role** — `AccessDenied` przy starcie kontenera (ECR, sekrety) to zwykle
  execution role; `AccessDenied` w logach aplikacji — task role.
- **Sekret w stanie Terraformu** — bucket stanu musi być prywatny, szyfrowany i wersjonowany
  (`infrastructure/bootstrap/main.tf:18-46` to robi).
- **`apply -auto-approve` na każdym pushu** — przy złej zmianie Terraform może coś usunąć
  (np. RDS) bez przeglądu planu.

## 7. Ćwiczenia

Instalacja: `brew install awscli`. Ćwiczenia 1–3 są darmowe i offline/LocalStack.
Ćwiczenia 4–5 wymagają konta AWS (same wywołania IAM/STS są bezpłatne).

**Ćwiczenie 1 — czytanie polityki (offline).** Na podstawie `infrastructure/modules/ecs/main.tf:123-186`
odpowiedz: czy Charging Session Service może
(a) `sns:Publish` na `chargeflow-dev-domain-events`, (b) `sqs:SendMessage` do `chargeflow-dev-notifications`,
(c) `dynamodb:DeleteItem` na tabeli powiadomień, (d) `sqs:ReceiveMessage` z DLQ,
(e) `s3:GetObject` z bucketu frontendu?
<details><summary>Odpowiedź</summary>
(a) tak; (b) nie — brak `sqs:SendMessage` (wysyła SNS na podstawie queue policy); (c) nie — tylko Put/Get/Query/Update;
(d) <b>tak</b> — wildcard `arn:aws:sqs:...:*` obejmuje też DLQ i każdą inną kolejkę; (e) nie — implicit deny.
</details>

**Ćwiczenie 2 — rozbierz token OIDC na części (offline).** Przykładowy payload tokenu GitHuba:

```json
{ "iss": "https://token.actions.githubusercontent.com",
  "aud": "sts.amazonaws.com",
  "sub": "repo:acme/chargeflow:ref:refs/heads/feature-x",
  "repository": "acme/chargeflow", "ref": "refs/heads/feature-x" }
```

Czy taki token przejdzie trust policy z `modules/iam-github/main.tf:26-35` przy `github_repo=acme/chargeflow`?
A token z `sub = repo:acme/chargeflow-fork:ref:refs/heads/main`? Zaproponuj warunek, który
przepuści tylko `deploy.yml` z environmentu `dev`.
<details><summary>Odpowiedź</summary>
Pierwszy — tak (`StringLike` z `*`). Drugi — nie, bo prefiks to `repo:acme/chargeflow:` z dwukropkiem, a `chargeflow-fork` nie pasuje.
Ciaśniej: `StringEquals = { "token.actions.githubusercontent.com:sub" = "repo:acme/chargeflow:environment:dev" }`.
</details>

**Ćwiczenie 3 — kto ma jakie poświadczenia lokalnie (LocalStack, darmowe).**

```bash
docker compose up -d
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=eu-central-1
aws --endpoint-url=http://localhost:4566 sts get-caller-identity
```

Zobaczysz fikcyjne konto `000000000000`. Zastanów się, dlaczego `SnsConfig.java:25`
(`DefaultCredentialsProvider`) działa bez zmian kodu i lokalnie (zmienne środowiskowe), i w ECS (task role).

**Ćwiczenie 4 — kim jestem na prawdziwym AWS (darmowe).** Po `aws configure sso` lub z kluczami:

```bash
aws sts get-caller-identity
aws iam get-role --role-name chargeflow-dev-github-deploy \
  --query 'Role.AssumeRolePolicyDocument'          # tylko jeśli dev jest wdrożony
```

**Ćwiczenie 5 — IAM Policy Simulator (darmowe, wymaga wdrożonego dev).**

```bash
ROLE_ARN=$(aws iam get-role --role-name chargeflow-dev-ecs-task-role --query Role.Arn --output text)
aws iam simulate-principal-policy --policy-source-arn "$ROLE_ARN" \
  --action-names sqs:ReceiveMessage dynamodb:DeleteItem ses:SendEmail \
  --resource-arns "arn:aws:sqs:eu-central-1:$(aws sts get-caller-identity --query Account --output text):chargeflow-dev-notifications-dlq"
```

Porównaj wynik (`allowed` / `implicitDeny`) z odpowiedziami z ćwiczenia 1. Potem — **jako ćwiczenie
na papierze/w gałęzi, bez commitowania** — przepisz task role na dwie role (per serwis) z ARN-ami
zamiast wildcardów.

## 8. Pytania kontrolne

1. Czym różni się rola IAM od użytkownika IAM i dlaczego CI/CD powinno używać roli?
   <details><summary>Odpowiedź</summary>Użytkownik ma stałe poświadczenia (access keys), które trzeba przechowywać i rotować; rola nie ma własnych kluczy — STS wydaje tymczasowe (np. na 1 h). Dzięki OIDC w GitHubie nie ma żadnego sekretu, który mógłby wyciec.</details>

2. Co sprawdza AWS, gdy GitHub Actions woła `AssumeRoleWithWebIdentity` na roli ChargeFlow?
   <details><summary>Odpowiedź</summary>Podpis tokenu względem zarejestrowanego OIDC providera (`token.actions.githubusercontent.com`), czy `aud` == `sts.amazonaws.com` (StringEquals) i czy `sub` pasuje do `repo:${github_repo}:*` (StringLike) — `modules/iam-github/main.tf:26-35`.</details>

3. Kontener startuje, ale nie może pobrać `DB_PASSWORD`. Którą rolę poprawiasz? A gdy w logach aplikacji widać `AccessDenied` przy `sns:Publish`?
   <details><summary>Odpowiedź</summary>Pierwsze — execution role (`modules/ecs/main.tf:88-100`, `secretsmanager:GetSecretValue`). Drugie — task role (`modules/ecs/main.tf:123-186`).</details>

4. Dlaczego queue policy SQS ma warunek `aws:SourceArn`?
   <details><summary>Odpowiedź</summary>Bez niego `sns.amazonaws.com` jako principal oznacza <i>dowolny</i> temat SNS w AWS (także cudzy). Warunek ogranicza do tematu `domain_events` (`modules/messaging/main.tf:48-54`).</details>

5. Po merge'u do `main` workflow jest zielony, ale w ECS nadal działa stara wersja. Dlaczego (w tym kodzie)?
   <details><summary>Odpowiedź</summary>`aws_ecs_service` ma `ignore_changes = [task_definition]` (`modules/ecs/main.tf:347-349`), a `deploy.yml` nie wywołuje `aws ecs update-service`. Terraform rejestruje nową rewizję task definition, ale serwis jej nie używa.</details>

6. Co jest ryzykowne w połączeniu `AdministratorAccess` + `sub = repo:org/repo:*`?
   <details><summary>Odpowiedź</summary>Każdy workflow z dowolnej gałęzi/PR-a/environmentu w repo dostaje admina na koncie, z pominięciem reguł ochrony environmentu `dev`. Lepiej: `StringEquals` na `repo:org/repo:environment:dev` (lub `ref:refs/heads/main`) i węższe uprawnienia.</details>
