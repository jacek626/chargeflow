# 02 — Sieć: VPC, subnety, NAT, security groups

> Na podstawie `infrastructure/modules/network/` oraz modułów, które z niego korzystają (`alb`, `ecs`, `rds`). Ścieżki są względne do katalogu głównego repozytorium.

---

## 1. Po co to jest

**VPC (Virtual Private Cloud)** to Twoja prywatna sieć w AWS, jakby wydzielone piętro w biurowcu:

- **subnety** to pokoje na tym piętrze,
- **publiczne subnety** to pokoje z drzwiami na korytarz (do internetu): stoi tam recepcja (ALB),
- **prywatne subnety** to pokoje bez drzwi na zewnątrz: siedzą tam aplikacje (ECS) i sejf z danymi (RDS),
- **Internet Gateway** to główne wejście do budynku,
- **NAT Gateway** to „poczta wychodząca”: pracownicy z zamkniętych pokoi mogą wysłać list na zewnątrz (np. pobrać obraz z ECR), ale nikt z zewnątrz nie zapuka bezpośrednio do ich drzwi,
- **security groups** to ochroniarze przy każdych drzwiach z listą, kogo wpuścić.

ARCHITECTURE.md mówi: „VPC isolates backend services from the public internet”. Ten moduł to realizuje.

---

## 2. Kluczowe pojęcia AWS

- **CIDR** — zakres adresów IP, np. `10.0.0.0/16` = 65 536 adresów (`10.0.0.0`–`10.0.255.255`). `/24` = 256 adresów, z czego AWS rezerwuje 5 w każdym subnecie (realnie 251).
- **Region / Availability Zone (AZ)** — region `eu-central-1` (Frankfurt) ma kilka fizycznie oddzielnych centrów danych (AZ): `eu-central-1a`, `1b`, `1c`. Subnet żyje zawsze w **jednej** AZ.
- **Subnet publiczny vs prywatny** — o tym, czy subnet jest „publiczny”, decyduje **tablica routingu**: jeśli ma trasę `0.0.0.0/0 → Internet Gateway`, to jest publiczny. Nie ma flagi „public”.
- **Internet Gateway (IGW)** — dwukierunkowe połączenie VPC z internetem dla zasobów z publicznym IP.
- **NAT Gateway** — pozwala zasobom bez publicznego IP **inicjować** połączenia wychodzące. Sam stoi w subnecie publicznym i potrzebuje Elastic IP.
- **Elastic IP (EIP)** — stały publiczny adres IPv4.
- **Route table** — reguły „ruch do X wysyłaj przez Y”. Każda zawiera niejawną trasę `local` dla całego CIDR VPC (ruch wewnątrz VPC).
- **Security Group (SG)** — stanowy (stateful) firewall na poziomie interfejsu sieciowego. Tylko reguły „allow”. Odpowiedź na dozwolony ruch przychodzący jest automatycznie wpuszczana z powrotem. Źródłem reguły może być CIDR **albo inny SG** — to kluczowy wzorzec.
- **NACL** — bezstanowy firewall na poziomie subnetu. **W ChargeFlow nie jest definiowany**, działają domyślne NACL (wszystko dozwolone).

---

## 3. Jak to działa w ChargeFlow

### 3.1 Zmienne modułu

`infrastructure/modules/network/variables.tf`:

| Zmienna | Domyślnie | Linia |
|---|---|---|
| `vpc_cidr` | `10.0.0.0/16` | 11-15 |
| `public_subnet_cidrs` | `["10.0.1.0/24", "10.0.2.0/24"]` | 17-21 |
| `private_subnet_cidrs` | `["10.0.10.0/24", "10.0.20.0/24"]` | 23-27 |
| `availability_zones` | `["eu-central-1a", "eu-central-1b"]` | 29-33 |
| `name_prefix`, `environment` | brak (wymagane) | 1-9 |

`environments/dev/main.tf:18-23` przekazuje tylko `name_prefix` i `environment`, więc **dev używa wszystkich wartości domyślnych**.

> Uwaga: zmienna `environment` jest zadeklarowana, ale nigdzie nie użyta w `modules/network/main.tf`. Nie jest to błąd, raczej pozostałość lub przygotowanie na przyszłość. Strefy AZ są wpisane na sztywno pod `eu-central-1`: zmiana `aws_region` bez zmiany `availability_zones` wywoła błąd przy `apply`.

### 3.2 VPC

```hcl
# infrastructure/modules/network/main.tf:3-13
resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true
```

- `enable_dns_support` — włącza resolver DNS Amazon w VPC (adres `10.0.0.2`).
- `enable_dns_hostnames` — instancje dostają nazwy DNS. Oba ustawienia są potrzebne, by np. endpoint RDS (`xxx.rds.amazonaws.com`) rozwiązywał się na prywatny IP oraz żeby w przyszłości działały VPC Interface Endpoints.

### 3.3 Internet Gateway i subnety publiczne

```hcl
# main.tf:26-40
resource "aws_subnet" "public" {
  count                   = length(var.public_subnet_cidrs)
  cidr_block              = var.public_subnet_cidrs[count.index]
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = true
```

- `count` tworzy 2 subnety: `chargeflow-dev-public-1` (10.0.1.0/24, AZ a) i `-public-2` (10.0.2.0/24, AZ b). Indeksy list CIDR i AZ muszą być zsynchronizowane.
- `map_public_ip_on_launch = true` — zasoby uruchamiane tu dostają automatycznie publiczny IP.
- `aws_route_table.public` (`main.tf:43-54`) ma trasę `0.0.0.0/0 → aws_internet_gateway.main`. To właśnie czyni te subnety publicznymi.
- `aws_route_table_association.public` (`main.tf:57-62`) przypina tablicę do obu subnetów.

**Dlaczego dwa subnety publiczne?** ALB wymaga subnetów w **co najmniej dwóch AZ**.

### 3.4 NAT Gateway — jeden, „cost-effective for dev”

```hcl
# main.tf:68-87
resource "aws_eip" "nat" { domain = "vpc" }

resource "aws_nat_gateway" "main" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public[0].id
  depends_on    = [aws_internet_gateway.main]
}
```

- Jeden NAT w `public[0]` (AZ `eu-central-1a`). Komentarz w linii 46 mówi wprost: „single, cost-effective for dev”.
- `depends_on` na IGW to jawna zależność. NAT nie referuje IGW w żadnym atrybucie, a bez działającego IGW nie miałby wyjścia do internetu. To jeden z nielicznych przypadków, gdzie `depends_on` jest uzasadnione (zalecane w dokumentacji providera).

### 3.5 Subnety prywatne

```hcl
# main.tf:92-125
resource "aws_subnet" "private" { ... }        # 10.0.10.0/24 (a), 10.0.20.0/24 (b)
resource "aws_route_table" "private" {
  route { cidr_block = "0.0.0.0/0"  nat_gateway_id = aws_nat_gateway.main.id }
}
```

- Brak `map_public_ip_on_launch`, brak trasy do IGW, więc nic tutaj nie jest osiągalne z internetu.
- **Jedna** tablica routingu dla obu subnetów prywatnych, obie kierują ruch wychodzący do tego samego NAT w AZ a.

**Po co subnety prywatne potrzebują NAT?** Zadania ECS Fargate (`assign_public_ip = false`, `infrastructure/modules/ecs/main.tf:329` i `:297`) muszą:
- pobrać obraz z ECR,
- odczytać hasło DB z Secrets Manager,
- wysyłać do SNS, odbierać z SQS, pisać do DynamoDB, wysyłać przez SES,
- pobrać JWKS z Cognito,
- wysyłać logi do CloudWatch.

To wszystko publiczne endpointy AWS. Ponieważ moduł nie definiuje **VPC Endpoints**, cały ten ruch idzie przez NAT.

### 3.6 Security groups — łańcuch zaufania

Trzy SG tworzą łańcuch, w którym każda warstwa wpuszcza **tylko poprzednią** (źródłem jest ID SG, a nie zakres IP):

**`alb`** (`main.tf:132-165`)
- ingress 80/tcp i 443/tcp z `0.0.0.0/0`, bo to publiczne wejście API,
- egress: wszystko (`protocol = "-1"`), żeby ALB mógł łączyć się z taskami ECS.

**`ecs`** (`main.tf:168-201`)
- ingress 8080/tcp, `security_groups = [aws_security_group.alb.id]` — Charging Session Service, tylko od ALB,
- ingress 8081/tcp, też tylko od ALB — Notification Service,
- egress: wszystko (potrzebne do RDS, NAT → usługi AWS).

**`rds`** (`main.tf:204-228`)
- ingress 5432/tcp tylko z `aws_security_group.ecs.id`,
- egress: wszystko.

Reguła „źródło = SG” oznacza: „wpuść ruch z dowolnego interfejsu sieciowego, który ma przypięty SG `ecs`”. Gdy ECS skaluje się do 10 tasków z różnymi IP, reguła nadal działa bez zmian.

> Uwaga: reguła dla portu 8081 (Notification Service z ALB) nie ma odpowiednika w ALB. `infrastructure/modules/alb/main.tf` definiuje tylko jeden target group na port 8080 (`charging_session`, linie 11-29). Notification Service nie jest podpięty pod ALB, więc ta reguła jest obecnie nieużywana. Port jest otwarty tylko dla ALB, więc ryzyko jest niewielkie, ale to niespójność.

> Uwaga: SG `rds` ma egress `0.0.0.0/0`. Baza danych nie inicjuje połączeń, a SG jest stanowy (odpowiedzi przechodzą bez reguły egress), więc tę regułę można by usunąć zgodnie z zasadą najmniejszych uprawnień.

### 3.7 Outputs i kto ich używa

`infrastructure/modules/network/outputs.tf` zwraca: `vpc_id`, `public_subnet_ids`, `private_subnet_ids` (przez splat `aws_subnet.private[*].id`), `alb_sg_id`, `ecs_sg_id`, `rds_sg_id`.

| Konsument (`environments/dev/main.tf`) | Co dostaje | Do czego |
|---|---|---|
| `module "alb"` (62-71) | `vpc_id`, `public_subnet_ids`, `alb_sg_id` | `aws_lb` z `internal = false` w subnetach publicznych (`alb/main.tf:3-11`), target group typu `ip` w VPC |
| `module "ecs"` (73-103) | `vpc_id`, `private_subnet_ids`, `ecs_sg_id` | `network_configuration` serwisów Fargate, bez publicznego IP |
| `module "rds"` (52-60) | `vpc_id`, `private_subnet_ids`, `rds_sg_id` | `aws_db_subnet_group` (`rds/main.tf:29-34`) + `publicly_accessible = false` (`rds/main.tf:71`) |

Ciekawostka: RDS ma `multi_az = false` (`rds/main.tf:70`), czyli baza żyje w jednej AZ. Mimo to **DB subnet group wymaga subnetów w co najmniej dwóch AZ**, to drugi powód, dla którego są dwa subnety prywatne.

Target group ALB ma `target_type = "ip"` (`alb/main.tf:20`). Przy Fargate (tryb sieci `awsvpc`) każdy task ma własny ENI i prywatny IP, więc ALB celuje bezpośrednio w IP tasków.

---

## 4. Diagram

```
                           Internet
                              │
                     ┌────────▼────────┐
                     │ Internet Gateway│  chargeflow-dev-igw
                     └────────┬────────┘
 VPC 10.0.0.0/16              │  rt-public: 0.0.0.0/0 → IGW
┌─────────────────────────────┼──────────────────────────────────────┐
│        AZ eu-central-1a     │              AZ eu-central-1b         │
│ ┌──────────────────────────┐│ ┌──────────────────────────┐         │
│ │ public-1  10.0.1.0/24    ││ │ public-2  10.0.2.0/24    │         │
│ │  [ALB node] sg:alb 80/443│◄─┤  [ALB node] sg:alb       │         │
│ │  [NAT GW + EIP]          ││ │                          │         │
│ └────────────▲─────────────┘│ └────────────┬─────────────┘         │
│              │ wychodzący    │              │ 8080 (sg:alb → sg:ecs)│
│              │ ruch          │              ▼                       │
│ ┌────────────┴─────────────┐  ┌──────────────────────────┐         │
│ │ private-1 10.0.10.0/24   │  │ private-2 10.0.20.0/24   │         │
│ │  [ECS task] sg:ecs       │  │  [ECS task] sg:ecs       │         │
│ │  [RDS] sg:rds ◄──5432────┼──┤  (subnet w DB subnet grp)│         │
│ └──────────────────────────┘  └──────────────────────────┘         │
│   rt-private (obie): 0.0.0.0/0 → NAT GW (w AZ a)                   │
└────────────────────────────────────────────────────────────────────┘

Łańcuch SG:  0.0.0.0/0 ─80/443─► sg:alb ─8080/8081─► sg:ecs ─5432─► sg:rds
```

---

## 5. Lokalnie vs AWS (dev)

- **Lokalnie** moduł `network` **nie jest używany**. `infrastructure/environments/local/main.tf` nie wywołuje go. Postgres działa w Dockerze na `localhost:5432`, serwisy na hoście, a LocalStack na `localhost:4566`. Izolację sieciową zastępuje… jej brak: wszystko jest na Twoim laptopie.
- **W dev** pełny moduł tworzy 2+2 subnety, IGW, NAT, EIP i 3 SG.
- LocalStack emuluje API EC2/VPC (możesz tam „utworzyć” VPC i SG), ale to tylko metadane. Nie ma prawdziwego routingu ani filtrowania pakietów. Dobre do nauki Terraform, nie do testowania reguł firewall.

---

## 6. Koszty i pułapki

**Koszty (eu-central-1, rząd wielkości; sprawdzaj aktualny cennik)**
- **NAT Gateway** to zwykle największa „niespodzianka” w dev: opłata godzinowa (ok. kilka centów/h, czyli ~30–40 USD/mies.) **plus** opłata za każdy GB przetworzony. Płacisz, nawet gdy nic nie działa.
- **Publiczne IPv4** (EIP NAT-a, adresy węzłów ALB) są płatne godzinowo od 2024 r.
- **Pobieranie obrazów z ECR przez NAT** jest liczone jako przetworzone GB. Przy częstych deployach to się sumuje. Typowe rozwiązanie to VPC Endpoints (Gateway endpoint dla S3 jest darmowy; ECR pobiera warstwy z S3). **W kodzie nie ma żadnych VPC Endpoints.**
- **Ruch między AZ**: task w `private-2` (AZ b) wychodzi przez NAT w AZ a, co daje dodatkową opłatę za cross-AZ transfer.
- Sam VPC, subnety, route tables, SG i IGW są **darmowe**.

**Pułapki**
1. **Single NAT = single point of failure.** Awaria AZ a odcina wyjście do internetu taskom w AZ b (nie pobiorą obrazów, sekretów, nie wyślą do SNS). Dla dev to akceptowalny kompromis (komentarz w kodzie), dla prod potrzebny jest NAT per AZ i tablica routingu per AZ.
2. **Zapomniane środowisko dev.** `terraform destroy` lub przynajmniej świadomość, że NAT nalicza opłaty 24/7.
3. **Nakładające się CIDR.** `10.0.0.0/16` to najpopularniejszy zakres na świecie. Jeśli kiedyś zechcesz VPC peering/VPN z siecią firmową w tym samym zakresie, nie zadziała. Dla prod rozważ np. `10.20.0.0/16`.
4. **Zmiana `count`/kolejności list** w subnetach powoduje zniszczenie i odtworzenie subnetów (a z nimi RDS/ECS). `for_each` z kluczem po AZ byłby odporniejszy.
5. **Mylenie SG z NACL.** SG jest stanowy, więc nie dodawaj reguł egress „dla odpowiedzi”.
6. **`0.0.0.0/0` na 80 przy braku certyfikatu.** Gdy `acm_certificate_arn = ""`, ALB serwuje API po czystym HTTP (`alb/main.tf:48`), a JWT leci otwartym tekstem. OK do nauki, nie do prawdziwych użytkowników.

---

## 7. Ćwiczenia

Instalacja: `brew install awscli terraform`.

**Ćwiczenie 1 — matematyka CIDR w `terraform console` (darmowe, offline)**
```bash
cd infrastructure/environments/local && terraform init >/dev/null && terraform console -var="environment=local"
> cidrsubnet("10.0.0.0/16", 8, 1)      # → 10.0.1.0/24 (public-1)
> cidrsubnet("10.0.0.0/16", 8, 20)     # → 10.0.20.0/24 (private-2)
> cidrhost("10.0.10.0/24", 0)          # adres sieci
> pow(2, 32-24) - 5                    # użyteczne adresy w /24 w AWS
```

**Ćwiczenie 2 — moduł network na LocalStack (darmowe)**
Utwórz katalog roboczy **poza repo** (np. `~/tf-lab/network`) z plikiem `main.tf`:
```hcl
provider "aws" {
  region                      = "eu-central-1"
  skip_credentials_validation = true
  skip_requesting_account_id  = true
  skip_metadata_api_check     = true
}
module "network" {
  source      = "/Users/jacek/IdeaProjects/chargeflow/infrastructure/modules/network"
  name_prefix = "lab-local"
  environment = "local"
}
output "net" { value = module.network }
```
```bash
docker compose up -d localstack   # z katalogu repo
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_ENDPOINT_URL=http://localhost:4566
terraform init && terraform plan          # policz zasoby: powinno ich być 17
terraform apply -auto-approve             # tylko LocalStack!
aws ec2 describe-route-tables --query 'RouteTables[].Routes'
aws ec2 describe-security-groups --group-names lab-local-rds-sg
```

**Ćwiczenie 3 — zmiana wymiarowania**
W tym samym labie dodaj `availability_zones = ["eu-central-1a","eu-central-1b","eu-central-1c"]` i trzecie CIDR-y w obu listach. Uruchom `terraform plan` i zobacz, co zostaje dodane, a co zmienione. Następnie **zamień kolejność** CIDR-ów i obserwuj `must be replaced`.

**Ćwiczenie 4 — plan dev bez stawiania (darmowe, wymaga konta AWS)**
```bash
cd infrastructure/environments/dev
terraform init -backend=false     # albo z -backend-config, jeśli masz bucket
AWS_PROFILE=chargeflow-dev terraform plan -var="environment=dev" -var="github_repo=me/chargeflow" | grep 'module.network'
```
`plan` tylko czyta. Uwaga: przy `-backend=false` plan użyje pustego lokalnego state.

**Ćwiczenie 5 (KOSZTUJE)** — `apply` samego modułu network na prawdziwym AWS (`terraform apply -target=module.network`). NAT + EIP zaczną naliczać opłaty od razu. Zrób `terraform destroy -target=module.network` tego samego dnia.

---

## 8. Pytania kontrolne

**1. Co sprawia, że `chargeflow-dev-public-1` jest publiczny, a `private-1` nie?**
<details><summary>Odpowiedź</summary>
Tablica routingu: <code>rt-public</code> ma trasę <code>0.0.0.0/0 → Internet Gateway</code> (<code>network/main.tf:48-51</code>), a <code>rt-private</code> ma <code>0.0.0.0/0 → NAT Gateway</code>. Dodatkowo subnety publiczne mają <code>map_public_ip_on_launch = true</code>.
</details>

**2. Dlaczego reguła RDS używa `security_groups = [aws_security_group.ecs.id]` zamiast CIDR `10.0.10.0/24`?**
<details><summary>Odpowiedź</summary>
Bo wpuszcza wyłącznie zasoby z przypiętym SG <code>ecs</code>, niezależnie od IP. Inne zasoby w tym samym subnecie nie dostaną dostępu, a skalowanie tasków (nowe IP) nie wymaga zmian reguł.
</details>

**3. Po co jest NAT Gateway, skoro taski ECS niczego nie wystawiają do internetu?**
<details><summary>Odpowiedź</summary>
Do ruchu <b>wychodzącego</b>: pobranie obrazu z ECR, sekretu z Secrets Manager, wywołania SNS/SQS/DynamoDB/SES, JWKS z Cognito, logi CloudWatch. Bez NAT ani VPC Endpoints taski w ogóle by nie wystartowały.
</details>

**4. RDS ma `multi_az = false`. Dlaczego mimo to dostaje dwa subnety w dwóch AZ?**
<details><summary>Odpowiedź</summary>
<code>aws_db_subnet_group</code> wymaga subnetów w co najmniej dwóch AZ. Ułatwia to też późniejsze włączenie Multi-AZ bez przebudowy sieci.
</details>

**5. Co się stanie z Notification Service w AZ b, gdy padnie AZ a?**
<details><summary>Odpowiedź</summary>
Jedyny NAT stoi w <code>public[0]</code> (AZ a), a wspólna tablica routingu prywatnego kieruje cały ruch wychodzący przez niego. Task w AZ b straci dostęp do SQS/DynamoDB/SES (brak VPC Endpoints). To świadomy kompromis kosztowy dla dev.
</details>

**6. Czy w obecnym kodzie port 8081 jest faktycznie osiągalny przez ALB?**
<details><summary>Odpowiedź</summary>
Nie. SG <code>ecs</code> zezwala na 8081 z SG <code>alb</code>, ale moduł ALB ma tylko listener(y) kierujące do target group na 8080 (Charging Session Service). Reguła 8081 jest obecnie nieużywana.
</details>
