# 09 — Frontend na S3 + CloudFront

> Materiał edukacyjny na podstawie rzeczywistego kodu repozytorium. Odwołania do plików mają postać `ścieżka/plik:linia` względem katalogu głównego repo.

## 1. Po co to jest

Zbudowana aplikacja Angular to po prostu **paczka statycznych plików**: `index.html`, kilka plików `.js` / `.css` z hashem w nazwie i favicon. Nie potrzebuje serwera aplikacyjnego (Tomcat, Node) — wystarczy coś, co odda pliki po HTTPS.

Analogia: **S3 to magazyn centralny**, a **CloudFront to sieć paczkomatów** rozsianych po świecie. Klient odbiera paczkę z najbliższego paczkomatu (edge location). Gdy paczkomat jej nie ma, ściąga ją z magazynu i trzyma przez jakiś czas (TTL). Magazyn jest zamknięty dla publiczności — wpuszcza tylko kurierów CloudFront z ważną legitymacją (OAC + bucket policy).

Dla Java developera: to odpowiednik wrzucenia `dist/` do `src/main/resources/static` w Spring Boocie — tylko że zamiast JVM-a serwuje to globalny CDN, który kosztuje grosze i skaluje się sam.

## 2. Kluczowe pojęcia AWS

| Pojęcie | Znaczenie |
|---|---|
| **S3 bucket** | Magazyn obiektów (klucz → plik). Nazwa bucketu jest **globalnie unikalna** we wszystkich kontach AWS. |
| **Block Public Access** | Cztery flagi, które blokują upublicznienie bucketu przez ACL i polityki. „Pas bezpieczeństwa” przed wyciekiem danych. |
| **CloudFront distribution** | Konfiguracja CDN: skąd brać pliki (*origins*), jak je cache’ować (*cache behaviors*), jaki certyfikat TLS, jakie domeny (*aliases*). Dostaje domenę `dXXXX.cloudfront.net`. |
| **Origin** | Źródło treści dla CloudFront — tu bucket S3 (adres regionalny `bucket.s3.eu-central-1.amazonaws.com`). |
| **OAC (Origin Access Control)** | Następca OAI. CloudFront podpisuje żądania do S3 (SigV4), a bucket policy wpuszcza tylko tę konkretną dystrybucję. Bucket pozostaje prywatny. |
| **OAI (Origin Access Identity)** | Starszy mechanizm (specjalny „użytkownik” CloudFront). W ChargeFlow **nie** jest używany — jest nowszy OAC. |
| **Cache behavior / TTL** | Reguły cache’owania: które metody, czy przekazywać query string/cookies, `min_ttl` / `default_ttl` / `max_ttl`. CloudFront respektuje `Cache-Control` z originu w granicach min/max. |
| **Custom error response** | Podmiana odpowiedzi błędu z originu (np. 403/404) na inną stronę i kod — klucz do routingu SPA. |
| **Invalidation** | Wymuszenie usunięcia obiektów z cache edge’ów przed upływem TTL. |
| **ACM certificate** | Certyfikat TLS. Dla CloudFront **musi być w `us-east-1`**, niezależnie od regionu reszty infrastruktury. |
| **CORS** | Mechanizm przeglądarki: skrypt z originu A może czytać odpowiedzi z originu B tylko, jeśli B na to pozwoli nagłówkami `Access-Control-Allow-*`. |

## 3. Jak to działa w ChargeFlow

### 3.1 Bucket S3 — prywatny magazyn

```hcl
resource "aws_s3_bucket" "frontend" {
  bucket        = "${var.name_prefix}-frontend"   # w dev: chargeflow-dev-frontend
  force_destroy = true
```
(`infrastructure/modules/frontend/main.tf:3-8`)

- `force_destroy = true` — `terraform destroy` usunie bucket nawet z zawartością. Wygodne w dev (build i tak da się odtworzyć), niebezpieczne dla danych.
- Wersjonowanie włączone (`main.tf:13-19`) — każde `s3 sync` tworzy nowe wersje, a `--delete` zostawia stare jako *noncurrent versions*.
- Pełny Block Public Access (`main.tf:23-30`): wszystkie cztery flagi `true`. Bucket **nie** jest skonfigurowany jako „static website hosting” — nie ma `aws_s3_bucket_website_configuration`. To dobrze: dostęp tylko przez CloudFront.

> **Uwaga:** brak reguły lifecycle (`aws_s3_bucket_lifecycle_configuration`) na wersje nieaktualne — przy każdym deployu przybywa starych bundli, które leżą w S3 bez końca (mały, ale rosnący koszt).
>
> **Uwaga:** nazwa `chargeflow-dev-frontend` musi być globalnie unikalna. Jeśli ktoś na świecie już ją zajął, `apply` się wyłoży z `BucketAlreadyExists`.

### 3.2 OAC + bucket policy — legitymacja kuriera

```hcl
resource "aws_cloudfront_origin_access_control" "frontend" {
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}
```
(`main.tf:34-40`)

Bucket policy (`main.tf:43-64`):

```hcl
Principal = { Service = "cloudfront.amazonaws.com" }
Action    = "s3:GetObject"
Resource  = "${aws_s3_bucket.frontend.arn}/*"
Condition = { StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.frontend.arn } }
```

Dwa ważne szczegóły:

1. **Warunek `AWS:SourceArn`** — bez niego *każda* dystrybucja CloudFront (także cudza, w innym koncie) mogłaby czytać bucket. Z warunkiem — tylko nasza.
2. **Tylko `s3:GetObject`, bez `s3:ListBucket`.** Gdy obiekt nie istnieje, S3 nie może powiedzieć „404 Not Found” (zdradziłby, co jest w buckecie), więc zwraca **403 Access Denied**. To wyjaśnia, dlaczego w dystrybucji jest mapowanie *także* 403 → `index.html`.

### 3.3 Dystrybucja CloudFront

`main.tf:69-146`. Najważniejsze fragmenty:

```hcl
default_root_object = "index.html"     # GET / -> /index.html
aliases             = var.domain_aliases
origin {
  domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
  origin_id                = "s3-frontend"
  origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
}
```

**Cache behavior** (`main.tf:83-100`) — jedno, domyślne, dla wszystkich ścieżek:

```hcl
viewer_protocol_policy = "redirect-to-https"
allowed_methods        = ["GET", "HEAD", "OPTIONS"]
compress               = true            # gzip/brotli dla JS/CSS
forwarded_values { query_string = false  cookies { forward = "none" } }
min_ttl = 0   default_ttl = 3600   max_ttl = 86400
```

- `redirect-to-https` — żądanie HTTP dostaje 301 na HTTPS.
- Nieprzekazywanie query stringów i cookies → jeden obiekt w cache na ścieżkę (lepszy cache hit ratio). Dla statycznego SPA niczego nie tracimy.
- TTL: `default_ttl` 1 h działa, gdy origin nie wysyła `Cache-Control`. Gdy wysyła — CloudFront go respektuje, ale przycina do `[min_ttl, max_ttl]`. Bundle z `max-age=31536000` (patrz 3.5) będą więc trzymane na edge’u maks. 24 h, a w przeglądarce — rok.
- `forwarded_values` to **legacy** sposób konfiguracji cache. AWS zaleca *cache policies* (`cache_policy_id`, np. zarządzana `CachingOptimized`). Działa, ale warto wiedzieć, że to stary styl.

**Routing SPA** (`main.tf:102-117`):

```hcl
custom_error_response { error_code = 404  response_code = 200  response_page_path = "/index.html"  error_caching_min_ttl = 0 }
custom_error_response { error_code = 403  response_code = 200  response_page_path = "/index.html"  error_caching_min_ttl = 0 }
```

Użytkownik wchodzi wprost na `https://.../reservations`. W S3 nie ma obiektu `reservations` → S3 zwraca 403 (patrz 3.2) → CloudFront podmienia to na `index.html` z kodem 200 → Angular Router czyta URL i renderuje `ReservationListComponent` (`frontend/src/app/app.routes.ts:20-24`). To samo dotyczy `/auth/callback?code=...` po powrocie z Cognito — bez tej reguły logowanie by nie działało.

**Certyfikat** (`main.tf:128-143`) — dwa bloki `dynamic`, z których aktywny jest dokładnie jeden:

- jest `acm_certificate_arn` → własny certyfikat, `sni-only`, minimum `TLSv1.2_2021`;
- brak → `cloudfront_default_certificate = true` (`*.cloudfront.net`).

Opis zmiennej przypomina, że certyfikat **musi być w us-east-1** (`infrastructure/modules/frontend/variables.tf:14-18`).

> **Uwaga:** brak `price_class` → domyślnie `PriceClass_All` (wszystkie edge locations, najdroższy wariant). Dla dev wystarczyłby `PriceClass_100` (Europa + Ameryka Płn.).
>
> **Uwaga:** brak rekordów DNS — `infrastructure/modules/route53/` jest pusty. Ustawienie `frontend_domain_aliases` wymaga ręcznego rekordu CNAME/ALIAS i certyfikatu; alias bez certyfikatu zostanie odrzucony przez AWS.
>
> **Uwaga:** brak *response headers policy* (CSP, HSTS, X-Frame-Options), logowania dostępu i WAF. Dla dev akceptowalne, dla prod do uzupełnienia.

**Outputs** (`infrastructure/modules/frontend/outputs.tf:2-17`): nazwa bucketu, ID dystrybucji, domena. W środowisku dev wystawione jako `frontend_bucket_name`, `frontend_cloudfront_distribution_id`, `frontend_cloudfront_domain` (`infrastructure/environments/dev/outputs.tf:49-64`) — workflow deployu czyta je po `terraform apply`.

### 3.4 Moduł w środowisku

`infrastructure/environments/dev/main.tf:132-139` podłącza moduł z `acm_certificate_arn = var.frontend_acm_certificate_arn` i `domain_aliases = var.frontend_domain_aliases`; oba domyślnie puste (`infrastructure/environments/dev/variables.tf:118-129`). W środowisku `local` modułu **nie ma** — lokalnie frontend działa przez `ng serve`.

### 3.5 Deploy: `.github/workflows/deploy.yml`

Workflow uruchamia się na push do `main` lub ręcznie. Kolejność:

1. **OIDC do AWS** bez długożyjących kluczy (`deploy.yml:9-11`, `27-31`).
2. Build obrazów backendu i push do ECR.
3. **Build Angulara** (`deploy.yml:63-76`): `npm ci`, potem `npx ng build --configuration=production`. Wynik ląduje w `frontend/dist/chargeflow-web/browser/` (domyślny `outputPath` buildera `@angular/build:application` dla projektu `chargeflow-web`). Konfiguracja `production` włącza `outputHashing: "all"` (`frontend/angular.json:50`) — pliki JS/CSS dostają hash treści w nazwie.
4. `terraform apply` dla dev (`deploy.yml:85-99`).
5. Odczyt outputów do `$GITHUB_ENV` (`deploy.yml:102-106`).
6. **Upload do S3 w dwóch krokach** (`deploy.yml:109-118`):

```bash
aws s3 sync frontend/dist/chargeflow-web/browser/ "s3://$FRONTEND_BUCKET/" \
  --delete --cache-control "public,max-age=31536000,immutable" --exclude "index.html"
aws s3 cp frontend/dist/chargeflow-web/browser/index.html "s3://$FRONTEND_BUCKET/index.html" \
  --cache-control "no-cache,no-store,must-revalidate"
```

To klasyczny wzorzec dla SPA:
   - pliki z hashem (`main-ABC123.js`) nigdy się nie zmieniają pod tą samą nazwą → można je cache’ować „na zawsze” (`immutable`);
   - `index.html` ma zawsze tę samą nazwę i wskazuje na aktualne hashe → nie może być cache’owany. Przy `min_ttl = 0` CloudFront respektuje `no-store` i za każdym razem pyta S3.

7. **Invalidation** (`deploy.yml:120-124`): `aws cloudfront create-invalidation --paths "/*"` — czyści edge’e, m.in. ze starego `index.html` z czasów, gdy mógł być zcache’owany.

> **Uwaga:** `--delete` usuwa stare bundle **przed** podmianą `index.html`. Użytkownik, który ma otwartą starą wersję, przy lazy-loadingu trasy (`loadComponent` w `app.routes.ts`) poprosi o nieistniejący chunk → S3 zwróci 403 → CloudFront odda `index.html` z kodem 200 → przeglądarka dostanie HTML zamiast JS (`ChunkLoadError` / „Unexpected token '<'”). Bezpieczniej: najpierw upload nowych plików, potem `index.html`, a sprzątanie starych chunków odłożyć (np. lifecycle).
>
> **Uwaga:** `public/favicon.ico` nie ma hasha w nazwie, a i tak dostaje `max-age=31536000,immutable` — zmiana favicony nie dotrze do przeglądarek.
>
> **Uwaga:** rola deployu ma `AdministratorAccess` (`infrastructure/modules/iam-github/main.tf:46-48`). Do samego frontu wystarczyłyby `s3:PutObject/DeleteObject/ListBucket` na jednym buckecie i `cloudfront:CreateInvalidation`.

### 3.6 Skąd frontend wie, gdzie jest API?

Z jednego pliku — `frontend/src/environments/environment.ts:4-20`:

```ts
export const environment = {
  production: false,
  skipAuth: true,
  apiBaseUrl: 'http://localhost:8080',
  auth: { clientId: 'REPLACE_WITH_COGNITO_CLIENT_ID', cognitoDomain: 'REPLACE_WITH_COGNITO_DOMAIN', ... redirectUri: 'http://localhost:4201/auth/callback' ... }
};
```

Serwisy składają URL z `environment.apiBaseUrl` (np. `frontend/src/app/stations/station.service.ts:10`), a interceptor dokleja token tylko do URL-i z tym prefiksem (`frontend/src/app/auth/auth.interceptor.ts:8`).

> **Uwaga (bug blokujący dev):** nie ma `environment.prod.ts` ani `environment.development.ts`, a konfiguracja `production` w `frontend/angular.json:37-51` nie ma `fileReplacements`. Nie ma też runtime configu (np. `config.json` pobieranego przy starcie). Wniosek: **bundle wdrażany na CloudFront ma `apiBaseUrl = http://localhost:8080`, `skipAuth = true` i placeholdery Cognito**. Przeglądarka użytkownika będzie wołać *swój własny* localhost, a guardy będą próbować `/dev/token`. Pipeline nie podstawia też outputów Terraform (`alb_dns_name`, `cognito_client_id`) do builda — zauważ, że build (krok 3) odbywa się **przed** `terraform apply` (krok 4), więc nie mógłby ich użyć w obecnej kolejności.

Typowe rozwiązania (do wyboru w osobnym zadaniu): (a) `fileReplacements` + `environment.prod.ts` generowany w CI z outputów Terraform; (b) runtime config — `config.json` wgrywany do S3 po `apply` i czytany przez `APP_INITIALIZER`; (c) API za tym samym CloudFrontem pod `/api/*` (drugi origin = ALB) i względny `apiBaseUrl`.

### 3.7 CORS

Frontend z `https://dXXXX.cloudfront.net` i API z `http://<alb>.elb.amazonaws.com` to **różne originy**, więc przeglądarka wymaga CORS. Żądania z nagłówkiem `Authorization` są „nieproste” → najpierw idzie preflight `OPTIONS`.

Backend: `charging-session-service/src/main/java/pl/chargeflow/session/config/SecurityConfig.java:47-60` — dozwolone originy z właściwości `chargeflow.cors.allowed-origins`, metody GET/POST/PUT/PATCH/DELETE/OPTIONS, wszystkie nagłówki, `allowCredentials=false`, `maxAge=3600` (przeglądarka cache’uje preflight na 1 h). CORS zarejestrowany tylko dla `/api/**` i `/dev/**`.

Wartość domyślna: `${CORS_ALLOWED_ORIGINS:http://localhost:4200,http://localhost:4201}` (`charging-session-service/src/main/resources/application.yml:38`).

> **Uwaga:** definicja kontenera w ECS (`infrastructure/modules/ecs/main.tf:214-221`) **nie ustawia `CORS_ALLOWED_ORIGINS`**, więc na AWS backend akceptuje tylko localhost — origin CloudFront zostanie odrzucony (`403 Invalid CORS request` na preflight).
>
> **Uwaga:** bez certyfikatu ALB słucha tylko na HTTP (`infrastructure/modules/alb/main.tf:37-70`). Strona z HTTPS (CloudFront) nie może wołać `http://` API — przeglądarka zablokuje to jako *mixed content*, zanim CORS w ogóle zadziała. Opcja (c) z poprzedniej sekcji rozwiązuje oba problemy naraz.

## 4. Diagram

**Deploy (GitHub Actions):**

```
 git push main
     |
     v
 [GH Actions] --OIDC--> STS AssumeRoleWithWebIdentity --> tymczasowe credentials
     |
     |-- ng build --configuration=production  -> dist/chargeflow-web/browser/
     |-- terraform apply (dev)                -> S3, OAC, CloudFront, ...
     |-- terraform output                     -> FRONTEND_BUCKET, CF_DISTRIBUTION_ID
     |-- aws s3 sync (bez index.html, --delete, max-age=1y immutable) --> S3
     |-- aws s3 cp index.html (no-cache,no-store)                      --> S3
     '-- aws cloudfront create-invalidation "/*"                        --> CloudFront edges
```

**Request użytkownika (deep link):**

```
 Przeglądarka            CloudFront edge                  S3 (prywatny)
     |  GET /reservations       |                               |
     |------------------------->| cache miss                    |
     |                          |-- GET /reservations (SigV4/OAC)->|
     |                          |<-- 403 AccessDenied (brak klucza, brak ListBucket)
     |                          | custom_error_response 403:    |
     |                          |-- GET /index.html (SigV4) ---->|
     |                          |<-- 200 index.html (no-store) --|
     |<-- 200 index.html -------|                               |
     |  GET /main-ABC123.js     |                               |
     |------------------------->| cache hit (TTL <= 24h) -------|
     |<-- 200 JS (max-age=1y) --|                               |
     |                                                          |
     | Angular Router -> /reservations -> HttpClient -> apiBaseUrl (+Bearer)
     |   ---> preflight OPTIONS + GET do API (inny origin => CORS) ---> ALB -> ECS
```

## 5. Lokalnie vs AWS (dev)

| Aspekt | Lokalnie | AWS dev |
|---|---|---|
| Serwowanie | `ng serve --port 4201` (`frontend/package.json:6`), dev server Angulara robi fallback SPA sam | S3 + CloudFront, fallback przez `custom_error_response` 403/404 |
| HTTPS | nie (`http://localhost:4201`) | tak, `*.cloudfront.net` lub własna domena z ACM (us-east-1) |
| Cache | brak, live reload | edge TTL ≤ 24 h dla bundli, `index.html` bez cache, invalidation po deployu |
| API URL | `http://localhost:8080` z `environment.ts` | **ten sam** `http://localhost:8080` (brak podmiany — bug) |
| CORS | `localhost:4200/4201` dozwolone domyślnie | origin CloudFront niedozwolony (brak `CORS_ALLOWED_ORIGINS`) |
| Infrastruktura w Terraform | moduł `frontend` nieużywany w `environments/local` | `environments/dev/main.tf:132-139` |

## 6. Koszty i pułapki

**Koszty (orientacyjnie, sprawdź aktualny cennik)**
- S3: kilka MB bundli — grosze. Uważaj na kumulujące się wersje (wersjonowanie bez lifecycle).
- CloudFront: transfer i liczba requestów; stały darmowy pakiet (obecnie 1 TB transferu i 10 mln requestów miesięcznie) w zupełności wystarcza na dev. `PriceClass_All` ma znaczenie dopiero przy dużym ruchu.
- Invalidations: pierwsze 1000 ścieżek miesięcznie gratis; `/*` liczy się jako **jedna** ścieżka.
- ACM: publiczne certyfikaty dla CloudFront — bez opłat. Route 53 hosted zone — opłata miesięczna (tu nieużywana).

**Pułapki**
- **Certyfikat w złym regionie.** Dla CloudFront tylko `us-east-1`; dla ALB — region ALB (`eu-central-1`). Dwa różne certyfikaty.
- **Zbyt agresywny fallback SPA.** Mapowanie 403/404 → `index.html` 200 dotyczy całej dystrybucji. Brakujący plik JS zwróci HTML z kodem 200, co utrudnia diagnozę. Gdybyś kiedyś dodał `/api/*` jako drugi origin, jego 403/404 też zostałyby podmienione na `index.html`.
- **Zapomniana invalidacja / zły `Cache-Control` na `index.html`** = użytkownicy widzą starą wersję.
- **Tworzenie dystrybucji trwa** (kilka–kilkanaście minut) — `terraform apply` czeka.
- **Publiczny bucket „na skróty”.** Nie wyłączaj Block Public Access, żeby „szybko zadziałało”; problem 403 rozwiązuje się poprawnym OAC i bucket policy.
- **Build-time config w SPA** — patrz 3.6.

## 7. Ćwiczenia

Przygotowanie: `brew install awscli terraform` (aws CLI nie jest jeszcze zainstalowane), Node 20+.

**Ćw. 1 — Zbuduj produkcyjny bundle i znajdź bug z API URL (darmowe)**

```bash
cd frontend
npm ci
npx ng build --configuration=production
ls -la dist/chargeflow-web/browser/            # index.html + pliki z hashem
grep -o 'localhost:8080' dist/chargeflow-web/browser/*.js | sort | uniq -c
grep -o 'REPLACE_WITH_COGNITO_[A-Z_]*' dist/chargeflow-web/browser/*.js | sort -u
```

Wyjaśnij, dlaczego te stringi są w bundlu, który trafi na CloudFront. Zaproponuj (na papierze) jedno z rozwiązań z sekcji 3.6.

**Ćw. 2 — Odtwórz krok deployu na LocalStack S3 (darmowe)**

```bash
./scripts/start-local.sh          # startuje LocalStack
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=eu-central-1
EP=--endpoint-url=http://localhost:4566
aws $EP s3 mb s3://cf-frontend-test
aws $EP s3 sync frontend/dist/chargeflow-web/browser/ s3://cf-frontend-test/ \
  --delete --cache-control "public,max-age=31536000,immutable" --exclude "index.html"
aws $EP s3 cp frontend/dist/chargeflow-web/browser/index.html s3://cf-frontend-test/index.html \
  --cache-control "no-cache,no-store,must-revalidate"
aws $EP s3api head-object --bucket cf-frontend-test --key index.html | jq .CacheControl
aws $EP s3api head-object --bucket cf-frontend-test --key "$(ls frontend/dist/chargeflow-web/browser | grep '^main-.*\.js$')" | jq .CacheControl
```

Porównaj nagłówki i wytłumacz, czemu są różne. (CloudFront w LocalStack Community nie jest dostępny — tę część ćwiczysz tylko na AWS.)

**Ćw. 3 — CORS preflight lokalnie (darmowe)**

```bash
curl -i -X OPTIONS http://localhost:8080/api/reservations \
  -H 'Origin: http://localhost:4201' \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: authorization'     # 200 + Access-Control-Allow-Origin
curl -i -X OPTIONS http://localhost:8080/api/reservations \
  -H 'Origin: https://d111111abcdef8.cloudfront.net' \
  -H 'Access-Control-Request-Method: GET'                 # 403 Invalid CORS request
```

Uruchom serwis z `CORS_ALLOWED_ORIGINS=https://d111111abcdef8.cloudfront.net` i powtórz drugie wywołanie. Którą zmienną trzeba by dodać w `infrastructure/modules/ecs/main.tf`?

**Ćw. 4 — Przeczytaj plan Terraform modułu `frontend` (plan darmowy, `apply` KOSZTUJE)**

```bash
cd infrastructure/environments/dev
terraform init -backend=false && terraform validate
# z credentials AWS (plan nic nie tworzy):
terraform plan -target=module.frontend
```

W planie znajdź: `origin_access_control_id`, oba `custom_error_response`, `viewer_certificate`, `price_class` (jaką wartość domyślną pokazuje?) i warunek `AWS:SourceArn` w bucket policy.

**Ćw. 5 — Sprawdź działający CloudFront (KOSZTUJE: wymaga wdrożonego dev; sam CloudFront/S3 w darmowym pakiecie, ale reszta dev — NAT/ALB/RDS/ECS — jest płatna)**

```bash
CF=$(terraform output -raw frontend_cloudfront_domain)
BUCKET=$(terraform output -raw frontend_bucket_name)
curl -sI "http://$CF/"                       # 301 -> https
curl -sI "https://$CF/"                      # 200, x-cache: Miss/Hit from cloudfront
curl -sI "https://$CF/reservations"          # 200 (fallback SPA z 403)
curl -sI "https://$BUCKET.s3.eu-central-1.amazonaws.com/index.html"   # 403 — bucket prywatny
aws cloudfront create-invalidation --distribution-id "$(terraform output -raw frontend_cloudfront_distribution_id)" --paths "/index.html"
```

Wywołaj `curl -sI "https://$CF/main-*.js"` (z prawdziwą nazwą) dwukrotnie i obserwuj nagłówki `x-cache` oraz `age`. Po ćwiczeniu: `terraform destroy`.

## 8. Pytania kontrolne

**1. Dlaczego dystrybucja mapuje na `index.html` nie tylko 404, ale też 403?**
<details><summary>Odpowiedź</summary>
Bucket jest prywatny, a polityka daje CloudFront wyłącznie <code>s3:GetObject</code>, bez <code>s3:ListBucket</code>. Dla nieistniejącego klucza (np. <code>/reservations</code>) S3 zwraca wtedy 403 zamiast 404, żeby nie zdradzać zawartości bucketu. Bez mapowania 403 deep linki i powrót z Cognito na <code>/auth/callback</code> kończyłyby się błędem.
</details>

**2. Co dokładnie robi OAC i po co warunek `AWS:SourceArn` w bucket policy?**
<details><summary>Odpowiedź</summary>
OAC sprawia, że CloudFront podpisuje żądania do S3 (SigV4) jako usługa <code>cloudfront.amazonaws.com</code>. Bucket policy wpuszcza tego principala, a warunek <code>AWS:SourceArn</code> zawęża dostęp do jednej, naszej dystrybucji — bez niego mogłaby czytać dowolna dystrybucja CloudFront, także w obcym koncie.
</details>

**3. Dlaczego deploy wgrywa `index.html` osobno i z innym `Cache-Control` niż resztę plików?**
<details><summary>Odpowiedź</summary>
Pliki JS/CSS mają hash treści w nazwie (<code>outputHashing: all</code>), więc nowa wersja = nowa nazwa; można je cache'ować rok jako <code>immutable</code>. <code>index.html</code> ma stałą nazwę i wskazuje aktualne hashe, więc musi być zawsze świeży (<code>no-cache,no-store</code>). Gdyby był cache'owany, użytkownicy ładowaliby starą aplikację.
</details>

**4. Certyfikat ACM dla `app.chargeflow.dev` jest w `eu-central-1`. Czy CloudFront go użyje?**
<details><summary>Odpowiedź</summary>
Nie. CloudFront jest usługą globalną i przyjmuje certyfikaty ACM wyłącznie z <code>us-east-1</code> (zob. opis zmiennej w <code>infrastructure/modules/frontend/variables.tf:14-18</code>). Certyfikat w <code>eu-central-1</code> nadaje się do ALB.
</details>

**5. Wymień trzy powody, dla których obecnie wdrożony frontend na CloudFront nie porozmawia z API na AWS dev.**
<details><summary>Odpowiedź</summary>
(1) Bundle produkcyjny ma <code>apiBaseUrl = http://localhost:8080</code> i <code>skipAuth = true</code> — brak <code>fileReplacements</code>/runtime configu. (2) ECS nie ustawia <code>CORS_ALLOWED_ORIGINS</code>, więc origin CloudFront jest odrzucany przez CORS. (3) Bez certyfikatu ALB działa po HTTP, a strona HTTPS nie może wołać <code>http://</code> (mixed content). Dodatkowo callback Cognito wskazuje <code>app.chargeflow.dev</code>, a domena nie jest skonfigurowana.
</details>

**6. Po co invalidation `/*`, skoro `index.html` i tak ma `no-store`?**
<details><summary>Odpowiedź</summary>
To zabezpieczenie: czyści edge'e z obiektów zcache'owanych wcześniej (np. <code>index.html</code> sprzed wprowadzenia nagłówków albo plików bez hasha, jak <code>favicon.ico</code>). Kosztowo jest tanie — <code>/*</code> liczy się jako jedna ścieżka z 1000 darmowych miesięcznie. Bundli z hashem invalidation nie dotyczy w praktyce, bo nowe mają nowe nazwy.
</details>
