# 01 — Terraform w ChargeFlow

> Materiał edukacyjny oparty na faktycznym kodzie w `infrastructure/`. Wszystkie odwołania `plik:linia` są względem katalogu głównego repozytorium.

---

## 1. Po co to jest

Wyobraź sobie, że infrastruktura AWS to baza danych, a Terraform to **Flyway/Liquibase dla chmury**. Zamiast klikać w konsoli AWS („ręczna migracja na produkcji”), opisujesz *docelowy stan* w plikach `.tf`, a Terraform:

1. czyta ten opis (kod),
2. porównuje go z tym, co pamięta o świecie (**state**),
3. odpytuje AWS, jak jest naprawdę (**refresh**),
4. wylicza różnicę (**plan**) i — po Twojej akceptacji — ją wykonuje (**apply**).

Różnica względem Flyway: Terraform jest **deklaratywny**. Nie piszesz „dodaj kolumnę”, tylko „tabela ma mieć te kolumny” — Terraform sam wylicza kroki. Dlatego plik state jest tak ważny: to „tabela `flyway_schema_history`” Terraforma.

W ChargeFlow Terraform opisuje całą infrastrukturę V1 (VPC, ECS, RDS, SNS/SQS, Cognito, CloudFront…) w trzech środowiskach: `local` (LocalStack), `dev` (prawdziwe AWS) i `prod` (na razie szkielet).

---

## 2. Kluczowe pojęcia

| Pojęcie | Co to jest | Analogia Java/Spring |
|---|---|---|
| **Provider** | Plugin, który umie gadać z API (tu `hashicorp/aws`) | Sterownik JDBC |
| **Resource** | Pojedynczy obiekt w chmurze (`aws_vpc`, `aws_sqs_queue`) | Encja |
| **Data source** | Odczyt czegoś, co już istnieje (np. `aws_caller_identity`) | `SELECT` bez `INSERT` |
| **Module** | Katalog z `.tf` przyjmujący zmienne i zwracający outputy | Klasa/biblioteka z konstruktorem i getterami |
| **Root module** | Katalog, w którym uruchamiasz `terraform` (tu `environments/<env>`) | `main()` / aplikacja Spring Boot |
| **Variable / Output / Local** | Wejście / wyjście / stała pomocnicza modułu | Parametr konstruktora / getter / `private final` |
| **State** | JSON z mapowaniem „zasób w kodzie ↔ ID w AWS” | `flyway_schema_history` |
| **Backend** | Gdzie trzymany jest state (lokalny plik, S3…) | Gdzie leży baza z historią migracji |
| **State locking** | Blokada, by dwie osoby nie robiły `apply` naraz | `SELECT … FOR UPDATE` |
| **Lock file** (`.terraform.lock.hcl`) | Przypięte wersje i hashe providerów | `mvnw` + checksumy zależności |

Ważne operatory wersji: `~> 6.64.0` oznacza `>= 6.64.0, < 6.65.0` (tylko patch), a `~> 1.11` oznacza `>= 1.11, < 2.0` (dowolny minor w 1.x).

---

## 3. Jak to działa w ChargeFlow

### 3.1 Struktura katalogów

```
infrastructure/
├── bootstrap/            # jednorazowo: bucket S3 + tabela DynamoDB na state
├── modules/              # klocki wielokrotnego użytku
│   ├── network/  ecr/  ecs/  alb/  rds/  messaging/  dynamodb/
│   ├── cognito/  frontend/  monitoring/  ses/  iam-github/
│   └── api-gateway/  lambda/  kms/  route53/   # PUSTE (tylko .gitkeep)
└── environments/
    ├── local/            # LocalStack, state lokalny
    ├── dev/              # prawdziwe AWS, state w S3
    └── prod/             # tylko szkielet — brak main.tf
```

Moduły `api-gateway`, `lambda`, `kms` i `route53` to **puste katalogi** (tylko `.gitkeep`). Są wymienione w `infrastructure/CLAUDE.md` jako planowane usługi, ale nie mają jeszcze żadnego kodu i nikt ich nie wywołuje.

### 3.2 Bootstrap — problem „jajka i kury”

Remote state ma leżeć w S3, ale ten bucket też trzeba jakoś stworzyć. Rozwiązanie: osobny, mały root module `infrastructure/bootstrap/`, uruchamiany **raz, ręcznie**, z **lokalnym** state.

```hcl
# infrastructure/bootstrap/main.tf:8
resource "aws_s3_bucket" "tfstate" {
  bucket        = var.state_bucket_name
  force_destroy = false
```

- `force_destroy = false` (`bootstrap/main.tf:10`) — `terraform destroy` nie usunie bucketu, jeśli są w nim pliki. Ochrona przed skasowaniem state'u wszystkich środowisk.
- `aws_s3_bucket_versioning` z `status = "Enabled"` (`bootstrap/main.tf:18-24`) — każda zapisana wersja state'u jest zachowana. Jak zepsujesz state, możesz wrócić do poprzedniej wersji obiektu.
- `aws_s3_bucket_server_side_encryption_configuration` z `AES256` (`bootstrap/main.tf:27-36`) — szyfrowanie SSE-S3. State może zawierać sekrety (np. wygenerowane hasła), więc szyfrowanie to minimum.
- `aws_s3_bucket_public_access_block` z czterema `true` (`bootstrap/main.tf:39-46`) — bucket nigdy nie stanie się publiczny, nawet przez pomyłkę w polityce.
- `aws_dynamodb_table.tfstate_lock` (`bootstrap/main.tf:52-65`) — tabela z kluczem `LockID` typu `S`; tego dokładnie oczekuje backend S3 przy klasycznym lockowaniu. `PAY_PER_REQUEST` = płacisz za żądania, a nie za stałą przepustowość (przy kilku `plan`/`apply` dziennie to grosze).

Zmienna `state_bucket_name` nie ma wartości domyślnej (`bootstrap/variables.tf:10-13`), bo nazwy bucketów S3 są **globalnie unikalne** — opis sugeruje `chargeflow-tfstate-<account-id>`.

> Uwaga: od Terraform 1.11 backend S3 ma natywne lockowanie przez plik w S3 (`use_lockfile = true`), a parametr `dynamodb_table` jest oznaczony jako przestarzały (deprecated). Projekt wymaga `~> 1.11`, więc DynamoDB do lockowania nie jest już konieczne — to działa, ale warto o tym wiedzieć.

### 3.3 Środowisko `dev` — pliki root modułu

**`versions.tf`** (`infrastructure/environments/dev/versions.tf:2-19`)

```hcl
required_version = "~> 1.11"
aws    = { source = "hashicorp/aws",    version = "~> 6.64.0" }
random = { source = "hashicorp/random", version = "~> 3.6" }
```

Provider `random` jest potrzebny, bo moduł `rds` generuje hasło (`infrastructure/modules/rds/versions.tf:2-9`). Moduł deklaruje swoje wymagania, a root module musi je spełnić.

> Uwaga: `infrastructure/README.md` twierdzi, że ograniczenie wersji Terraform to `~> 1.16.2`. W kodzie wszędzie jest `~> 1.11` — README jest nieaktualne.

**`backend.tf`** (`infrastructure/environments/dev/backend.tf:12-19`)

```hcl
terraform {
  backend "s3" {
    key = "dev/terraform.tfstate"
  }
}
```

To jest **partial backend configuration**. W kodzie jest tylko `key` (ścieżka w buckecie), a `bucket`, `region` i `dynamodb_table` podajesz przy `terraform init -backend-config=...` (instrukcja w komentarzu, linie 2-9). Dlaczego? Bloki `backend` nie mogą używać zmiennych, a nazwa bucketu zawiera ID konta, którego nie chcemy hardkodować.

**`providers.tf`** (`infrastructure/environments/dev/providers.tf:2-30`)

- `region = var.aws_region` — domyślnie `eu-central-1` (Frankfurt).
- Brak kluczy w kodzie. Provider używa standardowego łańcucha poświadczeń: zmienne środowiskowe, `~/.aws/credentials`/`AWS_PROFILE`, rola IAM, OIDC z GitHub Actions. To ten sam mechanizm co `DefaultCredentialsProvider` w AWS SDK for Java.
- `default_tags { tags = local.common_tags }` — **każdy** zasób AWS tworzony tym providerem dostaje tagi `Project`, `Environment`, `ManagedBy`. Dzięki temu w Cost Explorer filtrujesz koszty po `Environment=dev`.
- `locals { name_prefix = "${var.project_name}-${var.environment}" }` — daje `chargeflow-dev`, z którego moduły budują nazwy (`chargeflow-dev-vpc`, `chargeflow-dev-alb`…).

**`variables.tf`** (`infrastructure/environments/dev/variables.tf`)

- `environment` ma **walidację** (linie 10-13): tylko `local|dev|prod`. Literówka `dve` zatrzyma `plan` z czytelnym błędem.
- `github_repo` (linie 1-4) nie ma wartości domyślnej → musisz ją podać (tfvars lub `-var`), inaczej Terraform zapyta interaktywnie.
- Obrazy kontenerów mają domyślnie `placeholder/...:latest` (linie 54-64) — pozwala to zrobić `plan` zanim istnieją obrazy w ECR.
- `acm_certificate_arn = ""` (linie 88-92) — pusty string działa jak przełącznik „bez HTTPS” w module ALB (`count`/`dynamic` zależne od `!= ""`).

**`terraform.tfvars.example`** — szablon. Prawdziwy `terraform.tfvars` jest w `.gitignore` (`*.tfvars` + `!*.tfvars.example`). Zwróć uwagę na komentarz w linii 16: certyfikat dla CloudFront **musi** być w `us-east-1`, niezależnie od regionu reszty.

### 3.4 Kompozycja modułów w `environments/dev/main.tf`

Root module jest „klejem”: wywołuje moduły i przekazuje outputy jednych jako wejścia drugich.

```hcl
# infrastructure/environments/dev/main.tf:70-78
module "rds" {
  source            = "../../modules/rds"
  name_prefix       = local.name_prefix
  vpc_id            = module.network.vpc_id
  subnet_ids        = module.network.private_subnet_ids
  security_group_id = module.network.rds_sg_id
}
```

Wyrażenie `module.network.private_subnet_ids` tworzy **niejawną zależność**: Terraform buduje graf (DAG) i wie, że RDS musi powstać po sieci. Nie piszesz `depends_on` — wynika to z referencji, jak wstrzykiwanie przez konstruktor w Springu.

Najbardziej „zależny” jest moduł `ecs` (`main.tf:94-129`): bierze dane z `network`, `alb` (target group), `rds` (host, port, ARN sekretu hasła), `messaging` (ARN topicu, URL kolejki), `dynamodb`, `cognito` (JWKS URI). Graf wygląda mniej więcej tak:

```
network ──┬──> rds ───────┐
          ├──> alb ───────┤
          └───────────────┼──> ecs
messaging ────────────────┤
dynamodb ─────────────────┤
cognito ──────────────────┘
ecr, ses, frontend, monitoring, iam_github  (niezależne)
```

Moduły nie wiedzą, w jakim środowisku działają poza tym, co dostaną w zmiennych — zgodnie z regułą z README: „They have no environment-specific logic”.

### 3.5 Outputs

`infrastructure/environments/dev/outputs.tf` wystawia to, czego potrzebują ludzie i CI/CD: `github_deploy_role_arn` (do sekretu w GitHub), URL-e repozytoriów ECR, `alb_dns_name`, ID dystrybucji CloudFront (do invalidacji cache) itd. `rds_db_password_secret_arn` ma `sensitive = true` (linia 115) — Terraform nie wypisze go w konsoli (ale **nadal jest w state** w postaci jawnej!).

### 3.6 Środowisko `local`

`infrastructure/environments/local/main.tf` wywołuje tylko 4 moduły: `messaging`, `dynamodb`, `ses`, `monitoring`. Nie ma sieci, ECS, RDS — lokalnie Postgres działa w Dockerze, a serwisy na hoście. Nie ma też `backend.tf`, więc state jest lokalnym plikiem `terraform.tfstate` w tym katalogu (ignorowanym przez git). Szczegóły w `12-localstack-lokalnie.md`.

### 3.7 Środowisko `prod` — tylko szkielet

`infrastructure/environments/prod/` ma `versions.tf`, `providers.tf`, `variables.tf`, `outputs.tf` i `terraform.tfvars.example`, ale **nie ma `main.tf` ani `backend.tf`**. `outputs.tf` zawiera tylko komentarz „No infrastructure resources are provisioned yet”. `terraform plan` w prod nic nie stworzy. Gdy prod zostanie zaimplementowany, będzie potrzebny backend z innym `key` (np. `prod/terraform.tfstate`) — najlepiej wręcz w osobnym koncie AWS.

### 3.8 Workflow: fmt → validate → plan → apply

| Komenda | Co robi | Potrzebuje AWS? |
|---|---|---|
| `terraform init` | Pobiera providery (wg lock file), inicjalizuje backend i moduły | Tak dla backendu S3; `-backend=false` pomija |
| `terraform fmt -check -recursive` | Sprawdza formatowanie (jak Spotless) | Nie |
| `terraform validate` | Składnia, typy, referencje | Nie |
| `terraform plan` | Wylicza diff; nic nie zmienia | Tak (odczyt) |
| `terraform apply` | Wykonuje plan | Tak (zapis) — **kosztuje** |
| `terraform output` | Czyta outputy z state | Dostęp do state |

`infrastructure/CLAUDE.md` wymaga przed każdą zmianą `terraform fmt -check` i `terraform validate`.

---

## 4. Diagram

```
                 (raz, ręcznie, state lokalny)
                 infrastructure/bootstrap
                 ┌────────────────────────┐
                 │ S3: chargeflow-tfstate-│ versioning, SSE, no public
                 │ DynamoDB: ...-lock     │ LockID (S)
                 └───────────┬────────────┘
                             │ przechowuje state
                             ▼
 ┌──────────────────────────────────────────────────────────┐
 │ environments/dev  (backend "s3", key=dev/terraform.tfstate)
 │  versions.tf  providers.tf(default_tags)  variables.tf    │
 │  main.tf ──► module "network" ──► outputs ──► rds/alb/ecs │
 │          ──► messaging, dynamodb, cognito, ses, ecr, ...  │
 │  outputs.tf ──► alb_dns_name, ECR URLs, deploy role ARN   │
 └──────────────────────────────────────────────────────────┘

 environments/local  (state lokalny, LocalStack:4566)
   main.tf ──► messaging, dynamodb, ses, monitoring

 environments/prod   (szkielet: brak main.tf i backend.tf)
```

---

## 5. Lokalnie vs AWS (dev)

| Aspekt | `local` | `dev` |
|---|---|---|
| Cel | LocalStack na `localhost:4566` | Konto AWS |
| Backend | lokalny plik `terraform.tfstate` | S3 (`dev/terraform.tfstate`) + lock |
| Poświadczenia | `test`/`test` z env | profil / OIDC |
| Endpoint | `AWS_ENDPOINT_URL` z env | domyślny AWS |
| Moduły | 4 (messaging, dynamodb, ses, monitoring) | 12 |
| Provider flags | `skip_credentials_validation`, `skip_requesting_account_id`, `s3_use_path_style` | brak |
| Koszt | 0 zł | realne pieniądze |

---

## 6. Koszty i pułapki

**Koszty**
- Sam Terraform jest darmowy. Koszt powstaje przy `apply` w `dev`: najdroższe są tam NAT Gateway, ALB, RDS i ECS Fargate (patrz `02-siec-vpc.md`). `plan` tylko czyta, więc nic nie kosztuje.
- Bucket i tabela z bootstrapu kosztują praktycznie zero.

**Pułapki**
1. **Sekrety w state.** Hasło RDS z `random_password` trafia do state'u jawnym tekstem. `sensitive = true` ukrywa wartość tylko w konsoli. Dlatego bucket jest szyfrowany i prywatny.
2. **Zgubiony lokalny state = osierocone zasoby.** Jeśli skasujesz `terraform.tfstate` bootstrapu, Terraform „zapomni” o buckecie. Zasoby zostaną w AWS i będziesz musiał je zaimportować (`terraform import`).
3. **`init` w dev bez `-backend-config`** — Terraform zapyta interaktywnie o bucket albo zgłosi błąd. Do samej walidacji użyj `terraform init -backend=false`.
4. **Wersja Terraform.** README mówi 1.16.2, kod `~> 1.11`. Najbezpieczniej zainstalować najnowsze 1.x; lock file przypina tylko providery, nie sam Terraform.
5. **Nieaktualny README.** Mówi „State is local for now” i „modules (not yet implemented)”, a w kodzie jest już backend S3 w dev i 12 zaimplementowanych modułów. Ufaj kodowi.
6. **`apply` lokalnie a `apply` w dev.** Zawsze sprawdź, w którym katalogu jesteś i jakie masz `AWS_PROFILE`/`AWS_ENDPOINT_URL` w powłoce. Wyeksportowany `AWS_ENDPOINT_URL=http://localhost:4566` w shellu, w którym potem robisz `plan` dla dev, przekieruje go na LocalStack.

---

## 7. Ćwiczenia

Najpierw instalacja: `brew install awscli terraform` (ani `aws`, ani `terraform` nie są jeszcze zainstalowane).

**Ćwiczenie 1 — fmt i validate wszystkich środowisk (bezpieczne, darmowe)**
```bash
cd infrastructure
terraform fmt -check -recursive
for env in local dev prod; do
  (cd environments/$env && terraform init -backend=false -input=false >/dev/null && terraform validate)
done
```
Pytanie: dlaczego `prod` przechodzi walidację, choć nic nie definiuje?

**Ćwiczenie 2 — plan środowiska local na LocalStack (darmowe)**
```bash
docker compose up -d localstack
cd infrastructure/environments/local
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=eu-central-1 AWS_ENDPOINT_URL=http://localhost:4566
terraform init
terraform plan -var="environment=local"
```
Policz zasoby w planie i dopasuj je do plików w `modules/messaging`, `dynamodb`, `ses`, `monitoring`.

**Ćwiczenie 3 — walidacja zmiennej**
```bash
terraform plan -var="environment=staging"
```
Zobacz komunikat z bloku `validation` (`environments/local/variables.tf:10-13`).

**Ćwiczenie 4 — graf zależności dev (bez AWS)**
```bash
cd infrastructure/environments/dev
terraform init -backend=false
terraform graph | grep -E 'module\.(network|rds|ecs)' | head -30
```
Znajdź krawędzie `module.ecs` → `module.rds` i `module.rds` → `module.network`.

**Ćwiczenie 5 — `terraform console` (bez AWS)**
```bash
cd infrastructure/environments/local && terraform console -var="environment=local"
> local.name_prefix
> local.common_tags
```

**Ćwiczenie 6 (KOSZTUJE, opcjonalne)** — bootstrap na prawdziwym koncie:
`cd infrastructure/bootstrap && terraform apply -var="state_bucket_name=chargeflow-tfstate-<ACCOUNT_ID>"`. Koszt bliski zeru, ale to już prawdziwe zasoby — rób to dopiero gdy masz skonfigurowane konto i budżet (AWS Budgets).

---

## 8. Pytania kontrolne

**1. Dlaczego `infrastructure/bootstrap` ma state lokalny, a nie w S3?**
<details><summary>Odpowiedź</summary>
Bo tworzy właśnie ten bucket S3, w którym miałby leżeć state („jajko i kura”). Bootstrap uruchamia się raz, a jego lokalny state trzeba przechować bezpiecznie albo później zmigrować do utworzonego bucketu.
</details>

**2. Co oznacza `backend "s3" { key = "dev/terraform.tfstate" }` bez `bucket` i `region`?**
<details><summary>Odpowiedź</summary>
To partial configuration. Brakujące parametry podaje się przy <code>terraform init -backend-config="bucket=..."</code>. Blok backend nie obsługuje zmiennych, a tak unikamy hardkodowania nazwy bucketu z ID konta.
</details>

**3. Skąd Terraform wie, że moduł `ecs` trzeba tworzyć po `rds`?**
<details><summary>Odpowiedź</summary>
Z referencji <code>module.rds.host</code> i podobnych w <code>environments/dev/main.tf:112-116</code>. Terraform buduje z referencji graf zależności (DAG); jawne <code>depends_on</code> nie jest potrzebne.
</details>

**4. Do czego służy `default_tags` w `providers.tf`?**
<details><summary>Odpowiedź</summary>
Dokleja tagi <code>Project</code>, <code>Environment</code> i <code>ManagedBy</code> do każdego zasobu tworzonego tym providerem. Ułatwia to raportowanie kosztów, wyszukiwanie i odróżnianie zasobów zarządzanych przez Terraform od ręcznych.
</details>

**5. Czy `sensitive = true` na outpucie chroni hasło przed wyciekiem?**
<details><summary>Odpowiedź</summary>
Tylko częściowo: ukrywa wartość w wyjściu CLI. W pliku state wartość jest zapisana jawnie, dlatego state musi być szyfrowany, prywatny i mieć ograniczony dostęp.
</details>

**6. Które moduły są tylko planowane i jak to rozpoznać?**
<details><summary>Odpowiedź</summary>
<code>api-gateway</code>, <code>lambda</code>, <code>kms</code> i <code>route53</code>. Zawierają tylko <code>.gitkeep</code>, nie mają plików <code>.tf</code> i żadne środowisko ich nie wywołuje. Prod to też szkielet bez <code>main.tf</code>.
</details>
