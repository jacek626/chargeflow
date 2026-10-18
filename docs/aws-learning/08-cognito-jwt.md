# 08 — Amazon Cognito i JWT: logowanie w ChargeFlow

> Materiał edukacyjny na podstawie rzeczywistego kodu repozytorium. Odwołania do plików mają postać `ścieżka/plik:linia` względem katalogu głównego repo.

## 1. Po co to jest

Wyobraź sobie stację ładowania z bramką. Klient nie pokazuje bramce hasła — idzie do **recepcji** (Cognito), tam się legitymuje i dostaje **opaskę na rękę** (JWT) z nadrukiem: kim jest, do jakiej grupy należy i do kiedy opaska jest ważna. Bramka (Spring w Charging Session Service) nie dzwoni do recepcji przy każdym wejściu — sprawdza tylko, czy opaska ma **prawdziwy hologram** (podpis RSA weryfikowany kluczem publicznym z JWKS) i czy nie wygasła.

W ChargeFlow:

- **Cognito User Pool** przechowuje użytkowników (email + hasło) i grupy `CUSTOMER` / `OPERATOR`.
- **Angular** przekierowuje użytkownika na Hosted UI Cognito (OAuth2 Authorization Code + PKCE), odbiera tokeny i dokleja `Authorization: Bearer <access token>` do żądań API.
- **Spring Boot** działa jako *OAuth2 Resource Server*: waliduje podpis JWT, mapuje claim `cognito:groups` na role `ROLE_CUSTOMER` / `ROLE_OPERATOR` i autoryzuje endpointy.

Backend nie zna haseł i nie trzyma sesji — jest bezstanowy (`SessionCreationPolicy.STATELESS`).

## 2. Kluczowe pojęcia AWS

| Pojęcie | Znaczenie |
|---|---|
| **User Pool** | Katalog użytkowników + serwer autoryzacji OAuth2/OIDC. Ma własny `issuer`: `https://cognito-idp.<region>.amazonaws.com/<userPoolId>`. |
| **App Client** | „Aplikacja” uprawniona do korzystania z puli (odpowiednik `client_id` w OAuth2). Może być *public* (bez sekretu — SPA, mobile) albo *confidential* (z sekretem — backend). |
| **Hosted UI / domena** | Strony logowania hostowane przez AWS pod `https://<prefix>.auth.<region>.amazoncognito.com`. Udostępniają endpointy `/oauth2/authorize`, `/oauth2/token`, `/logout`. |
| **Grupy** | Etykiety użytkowników; trafiają do tokenów jako claim `cognito:groups` (tablica stringów). |
| **Authorization Code + PKCE** | Przeglądarka dostaje jednorazowy `code`, który wymienia na tokeny. PKCE (`code_verifier` / `code_challenge`) zastępuje sekret klienta — tylko ta karta, która zaczęła logowanie, zna `verifier`. |
| **ID token** | Token OIDC *dla klienta (frontendu)*: kim jest użytkownik. Zawiera m.in. `email`, `email_verified`, `cognito:username`, `cognito:groups`, `aud` = client_id, `token_use: "id"`. |
| **Access token** | Token *dla API*: co klient może zrobić. Zawiera `sub`, `cognito:groups`, `scope`, `client_id`, `username`, `token_use: "access"`. **Nie zawiera `email` ani `aud`.** |
| **Refresh token** | Długożyjący token do pobierania nowych access/ID tokenów bez ponownego logowania. |
| **JWKS** | JSON Web Key Set — publiczne klucze RSA puli pod `<issuer>/.well-known/jwks.json`. Każdy JWT ma w nagłówku `kid`, który wskazuje klucz do weryfikacji podpisu. |
| **Claims** | Pola w payloadzie JWT: standardowe (`iss`, `sub`, `exp`, `iat`, `aud`) i specyficzne dla Cognito (`cognito:groups`, `token_use`, `client_id`). |

Struktura JWT: `base64url(header).base64url(payload).base64url(signature)`. Header i payload **nie są szyfrowane** — każdy może je odczytać. Bezpieczeństwo daje wyłącznie podpis.

Domyślne czasy życia w Cognito (gdy nie ustawisz ich jawnie): access i ID token — 60 min, refresh token — 30 dni.

## 3. Jak to działa w ChargeFlow

### 3.1 Terraform: moduł `cognito`

**User Pool** — email jako login, automatyczna weryfikacja emaila, polityka haseł:

```hcl
resource "aws_cognito_user_pool" "main" {
  name = "${var.name_prefix}-users"
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]
  password_policy { minimum_length = 8  ... require_symbols = false ... }
```
(`infrastructure/modules/cognito/main.tf:4-31`)

**Grupy** `CUSTOMER` i `OPERATOR` (`infrastructure/modules/cognito/main.tf:35-46`). Komentarz w linii 28 opisuje intencję: claim `cognito:groups` → `ROLE_CUSTOMER` / `ROLE_OPERATOR` w Springu. Nazwy grup są pisane wielkimi literami, bo dokładnie taki string ląduje po prefiksie `ROLE_`.

**App Client** dla Angulara (`infrastructure/modules/cognito/main.tf:50-77`):

```hcl
  generate_secret = false                       # public client — SPA nie utrzyma sekretu
  allowed_oauth_flows                  = ["code"]  # tylko Authorization Code (bez implicit)
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_scopes                 = ["openid", "email", "profile"]
  supported_identity_providers         = ["COGNITO"]
  callback_urls = var.callback_urls
  logout_urls   = var.logout_urls
  explicit_auth_flows = ["ALLOW_USER_SRP_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"]
  prevent_user_existence_errors = "ENABLED"
```

- Brak sekretu + flow `code` = klasyczne ustawienie pod PKCE.
- `explicit_auth_flows` dotyczy logowania **przez API** (`InitiateAuth`), nie przez Hosted UI. Dozwolone jest tylko SRP i refresh.
- `prevent_user_existence_errors` — Cognito nie zdradza, czy dany email istnieje (ochrona przed enumeracją kont).

**Domena Hosted UI** (`infrastructure/modules/cognito/main.tf:81-84`): prefiks = `var.name_prefix` (w dev: `chargeflow-dev`). Prefiks musi być unikalny w regionie.

**Callback / logout URL**: domyślnie w module `http://localhost:4200/...` (`infrastructure/modules/cognito/variables.tf:16-28`), a w środowisku dev nadpisane na `https://app.chargeflow.dev/auth/callback` (`infrastructure/environments/dev/variables.tf:39-50`).

**Outputs** — `jwks_uri` i `issuer_uri` składane ręcznie (`infrastructure/modules/cognito/outputs.tf:28-38`). `jwks_uri` trafia do ECS jako zmienna środowiskowa `COGNITO_JWKS_URI` (`infrastructure/environments/dev/main.tf:123`, `infrastructure/modules/ecs/main.tf:220`).

> **Uwaga:** w module nie ma ustawień `access_token_validity` / `id_token_validity` / `refresh_token_validity` ani `token_validity_units` — obowiązują wartości domyślne Cognito (60 min / 60 min / 30 dni).
>
> **Uwaga:** region w outputach jest zahardkodowany (`local` → `us-east-1`, wszystko inne → `eu-central-1`), zamiast brać go z providera (`data "aws_region"`). Przy wdrożeniu w innym regionie URI będzie błędny.
>
> **Uwaga:** domyślny `callback_urls` modułu wskazuje port **4200**, a frontend działa na **4201** (`frontend/package.json:6`, `frontend/src/environments/environment.ts:16`). Dev używa `app.chargeflow.dev`, ale `frontend_domain_aliases` domyślnie jest puste — bez skonfigurowanej domeny callback nie zgadza się z adresem `*.cloudfront.net`, więc Cognito odrzuci przekierowanie (`redirect_mismatch`).

### 3.2 Angular: własna implementacja PKCE (bez biblioteki OIDC)

Projekt **nie używa** biblioteki typu `angular-oauth2-oidc` czy `angular-auth-oidc-client`. Cały flow jest napisany ręcznie w `frontend/src/app/auth/auth.service.ts` z użyciem Web Crypto API.

**Konfiguracja** — `frontend/src/environments/environment.ts:10-19`: `clientId`, `cognitoDomain` (placeholdery `REPLACE_WITH_...`), region, `redirectUri`, `scopes: 'openid email profile'`. Bazowy URL Hosted UI jest składany w `auth.service.ts:32`.

**Krok 1 — start logowania** (`auth.service.ts:64-78`):

```ts
const verifier = generateCodeVerifier();               // 32 losowe bajty, base64url
const challenge = await generateCodeChallenge(verifier); // SHA-256(verifier), base64url
sessionStorage.setItem(VERIFIER_KEY, verifier);
// ... response_type=code, client_id, redirect_uri, scope, code_challenge, code_challenge_method=S256
window.location.href = `${this.baseUrl}/oauth2/authorize?${params}`;
```

Helpery PKCE: `auth.service.ts:141-158`.

**Krok 2 — callback** — trasa `auth/callback` (`frontend/src/app/app.routes.ts:7-10`) ładuje `CallbackComponent`, który czyta `code` lub `error` z query stringa (`frontend/src/app/auth/callback/callback.ts:30-50`) i woła `handleCallback`.

**Krok 3 — wymiana kodu na tokeny** (`auth.service.ts:80-106`): `POST /oauth2/token` z `grant_type=authorization_code`, `code`, `code_verifier`, `client_id`, `redirect_uri`. Cognito liczy SHA-256 z `code_verifier` i porównuje z zapamiętanym `code_challenge`.

**Przechowywanie** (`auth.service.ts:119-126`): access token, ID token i `expiresAt` (z 30 s buforem) w `sessionStorage` pod kluczem `cf_tokens`.

**Interceptor** (`frontend/src/app/auth/auth.interceptor.ts:6-16`), rejestrowany w `frontend/src/app/app.config.ts:11`:

```ts
if (!req.url.startsWith(environment.apiBaseUrl)) return next(req); // token tylko do naszego API
const token = inject(AuthService).getAccessToken();
if (!token) return next(req);
return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
```

Sprawdzenie prefiksu URL chroni przed wysłaniem tokena do obcych domen. Do API idzie **access token** — poprawnie, bo to token przeznaczony dla resource servera.

**Guardy**:

- `authGuard` (`frontend/src/app/auth/auth.guard.ts:6-15`) — jeśli brak ważnego tokena: w trybie `skipAuth` pobiera lokalny dev token, w przeciwnym razie startuje `login()`.
- `operatorGuard` (`frontend/src/app/auth/operator.guard.ts:6-20`) — dodatkowo sprawdza `cognito:groups` z **ID tokena** (`getUserClaims()` parsuje `idToken`, `auth.service.ts:47-51`) i przekierowuje nie-operatorów na `/stations`.

Guardy to tylko UX. Prawdziwa autoryzacja jest w backendzie (zgodnie z `frontend/CLAUDE.md`: frontend nie jest źródłem prawdy dla autoryzacji).

**Wylogowanie** (`auth.service.ts:108-115`): czyści `sessionStorage` i przekierowuje na `/logout` Cognito z `logout_uri`.

> **Uwaga:** żądanie `/oauth2/authorize` nie zawiera parametru `state` — brak ochrony przed CSRF na etapie logowania (login CSRF). PKCE częściowo to łagodzi, ale `state` jest zalecany.
>
> **Uwaga:** `refresh_token` jest w typie `TokenResponse`, ale nigdy nie jest zapisywany ani używany — po ~60 min użytkownik musi przejść pełne logowanie od nowa.
>
> **Uwaga:** tokeny w `sessionStorage` są dostępne dla każdego skryptu na stronie — XSS = kradzież tokena. To typowy kompromis SPA; alternatywą jest wzorzec BFF z ciasteczkiem `HttpOnly`.

### 3.3 Spring: OAuth2 Resource Server

Zależności: `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server` (`charging-session-service/pom.xml:70-77`).

**Skąd klucze** — `charging-session-service/src/main/resources/application.yml:29-34`:

```yaml
spring.security.oauth2.resourceserver.jwt:
  jwk-set-uri: ${COGNITO_JWKS_URI:http://localhost:4566/eu-central-1_local/.well-known/jwks.json}
```

Spring Boot na tej podstawie tworzy `NimbusJwtDecoder`, który przy pierwszym żądaniu pobiera JWKS, cache’uje klucze i dobiera je po `kid`. Waliduje podpis oraz `exp`/`nbf`.

**Łańcuch filtrów** — `charging-session-service/src/main/java/pl/chargeflow/session/config/SecurityConfig.java:26-45`:

```java
.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
.requestMatchers("/dev/**").permitAll()
.requestMatchers("/api/stations", "/api/stations/**").permitAll()
.requestMatchers("/api/reservations", "/api/reservations/**").hasRole("CUSTOMER")
.requestMatchers("/api/charging-sessions", "/api/charging-sessions/**").hasRole("CUSTOMER")
.requestMatchers("/api/admin/**").hasRole("OPERATOR")
.anyRequest().denyAll()
```

`denyAll()` na końcu to dobry wzorzec *default deny* — nowy endpoint jest zablokowany, dopóki ktoś świadomie go nie dopisze.

**Mapowanie grup na role** (`SecurityConfig.java:62-71`):

```java
authoritiesConverter.setAuthoritiesClaimName("cognito:groups");
authoritiesConverter.setAuthorityPrefix("ROLE_");
```

Domyślnie Spring buduje authorities z claimu `scope` (`SCOPE_openid` itd.). Tu zastępujemy to grupami: `["CUSTOMER"]` → `ROLE_CUSTOMER`, a `hasRole("CUSTOMER")` sprawdza właśnie `ROLE_CUSTOMER`.

**Tożsamość użytkownika** — kontrolery biorą `sub` z tokena, nigdy z body (zgodnie ze specyfikacją): `ReservationController.java:35-57`, `ChargingSessionController.java:34-41`.

**Notification Service** ma tylko tymczasową konfigurację: health `permitAll`, reszta `denyAll`, bez JWT (`notification-service/src/main/java/pl/chargeflow/notification/config/SecurityConfig.java:10-27`). Zmienna `COGNITO_JWKS_URI` jest mu przekazywana w ECS (`infrastructure/modules/ecs/main.tf:288`), ale nie jest używana.

> **Uwaga (ważne):** skonfigurowane jest tylko `jwk-set-uri`, **bez `issuer-uri`** i bez walidacji `token_use` / `client_id`. Skutek: Spring akceptuje każdy JWT podpisany kluczem tej puli — także **ID token** zamiast access tokena oraz tokeny wydane innym app clientom tej samej puli. Rekomendacja: dodać `issuer-uri` (walidacja `iss`) i własny `OAuth2TokenValidator` sprawdzający `token_use == "access"` i `client_id`.
>
> **Uwaga (bug):** `ReservationController.java:36` czyta `jwt.getClaimAsString("email")`. Cognito **nie umieszcza `email` w access tokenie**, a frontend wysyła właśnie access token. Lokalnie działa (dev token ma `email`), ale na AWS email rezerwacji będzie pustym stringiem, co uderzy w powiadomienia email.
>
> **Uwaga:** specyfikacja mówi „All customer endpoints require the CUSTOMER role” (`docs/product-specs/chargeflow-spec.md:359`), a `/api/stations/**` jest `permitAll()` — świadome lub nie, to odstępstwo od dokumentacji.
>
> **Uwaga:** `/dev/**` jest `permitAll()` bezwarunkowo, także w dev/prod. Kontroler istnieje tylko w profilu `local`, więc w AWS dostaniesz 404, ale reguła powinna być warunkowa.

**CORS** — `SecurityConfig.java:47-60`: dozwolone originy z `chargeflow.cors.allowed-origins` (domyślnie `localhost:4200,4201`, `application.yml:38`), wszystkie nagłówki (w tym `Authorization`), `allowCredentials=false` (token idzie w nagłówku, nie w ciasteczku). Szczegóły CORS dla CloudFront — patrz plik 09.

### 3.4 Jak działa auth lokalnie

W repo są **dwa podejścia**, z czego realnie działa tylko jedno.

**A) Działające: self-signed JWT w profilu `local`.**

- `LocalDevAuthConfig.java:14-20` generuje przy starcie parę kluczy RSA 2048 z `kid = local-dev-key`.
- `LocalDevController.java:29-32` wystawia publiczny klucz jako `GET /dev/jwks.json`.
- `LocalDevController.java:34-61` (`POST /dev/token`) podpisuje token RS256 z claimami `sub=local-user-<rola>`, `email`, `cognito:groups=[ROLE]`, ważny 24 h, i zwraca go jednocześnie jako `access_token` i `id_token`.
- `application-local.yml:9` przestawia `jwk-set-uri` na `http://localhost:8080/dev/jwks.json` — serwis weryfikuje tokeny, które sam wystawił. Ta sama ścieżka kodu (`NimbusJwtDecoder` + konwerter grup) co na AWS — zmienia się tylko źródło kluczy.
- Frontend z `skipAuth: true` (`environment.ts:8`) woła `/dev/token` w guardach (`auth.service.ts:53-62`).
- `scripts/start-local.sh:106-108` to opisuje: „Auth: handled by Spring profile 'local' (self-signed JWT, no Cognito needed)”.

**B) Nieaktualne: skrypty LocalStack Cognito.**

`scripts/create-local-user.sh` i `scripts/get-local-token.sh` zakładają Cognito w LocalStack i czytają `terraform output cognito_user_pool_id` / `cognito_client_id` z `infrastructure/environments/local`.

> **Uwaga:** środowisko `infrastructure/environments/local/main.tf` **nie zawiera modułu `cognito`** (tylko messaging, dynamodb, ses, monitoring), a `outputs.tf` nie ma tych outputów — skrypty zakończą się błędem już na `terraform output`. Dodatkowo Cognito w LocalStack jest funkcją płatnej wersji (Pro), a `docker-compose.yml` uruchamia obraz bez tokenu licencyjnego. Ponadto `get-local-token.sh:26-28` używa `USER_PASSWORD_AUTH`, którego app client nie dopuszcza (`explicit_auth_flows` zawiera tylko SRP i refresh) — na prawdziwym Cognito to też by nie zadziałało. Traktuj te skrypty jako martwy kod / pozostałość.

## 4. Diagram

Pełna sekwencja na AWS (dev): logowanie + wywołanie API.

```
 Przeglądarka/Angular          Cognito Hosted UI                Spring (ECS, za ALB)
        |                              |                                 |
 [1] user wchodzi na /reservations     |                                 |
     authGuard: brak tokena            |                                 |
     verifier = random(32B)            |                                 |
     challenge = b64url(SHA256(verifier))                                |
     sessionStorage[cf_pkce_verifier] = verifier                         |
        |-- [2] GET /oauth2/authorize?response_type=code&client_id=..    |
        |       &redirect_uri=../auth/callback&code_challenge=..&S256 -->|
        |                              |                                 |
        |<-- [3] strona logowania -----|                                 |
        |--- [4] email + hasło ------->|  (sprawdza hasło, grupy)        |
        |<-- [5] 302 Location: /auth/callback?code=XYZ                   |
        |                              |                                 |
 [6] CallbackComponent czyta code      |                                 |
        |-- [7] POST /oauth2/token  grant_type=authorization_code,       |
        |        code=XYZ, code_verifier, client_id, redirect_uri ------>|
        |                              | SHA256(verifier) == challenge?  |
        |<-- [8] { access_token, id_token, refresh_token, expires_in } --|
 [9] sessionStorage[cf_tokens] = {access, id, expiresAt}                 |
        |                              |                                 |
 [10] HttpClient GET /api/reservations                                   |
      authInterceptor: Authorization: Bearer <access_token>              |
        |---------------------------------------------------------------->|
        |                              |   [11] BearerTokenAuthFilter    |
        |                              |   NimbusJwtDecoder: kid?        |
        |                              |<-- [12] GET /<pool>/.well-known/jwks.json
        |                              |--- klucze publiczne (cache) --->|
        |                              |   [13] weryfikacja RS256, exp   |
        |                              |   [14] cognito:groups=[CUSTOMER]|
        |                              |        -> ROLE_CUSTOMER         |
        |                              |   [15] hasRole("CUSTOMER") OK   |
        |                              |   [16] controller: jwt.getSubject()
        |<--------------------------------------- [17] 200 OK JSON -------|
```

Brak tokena → `401` (z nagłówkiem `WWW-Authenticate: Bearer`). Poprawny token, zła grupa → `403`.

## 5. Lokalnie vs AWS (dev)

| Aspekt | Lokalnie (profil `local`) | AWS dev |
|---|---|---|
| Wystawca tokenów | `POST /dev/token` w samym serwisie | Cognito User Pool `chargeflow-dev-users` |
| Logowanie | brak — guard pobiera token automatycznie (`skipAuth: true`) | Hosted UI, Authorization Code + PKCE |
| JWKS | `http://localhost:8080/dev/jwks.json` (`application-local.yml:9`) | `https://cognito-idp.eu-central-1.amazonaws.com/<poolId>/.well-known/jwks.json` (env `COGNITO_JWKS_URI`) |
| Klucz | RSA generowany przy każdym starcie (restart unieważnia stare tokeny) | Klucze zarządzane przez AWS |
| Ważność tokena | 24 h | 60 min (domyślna Cognito) |
| `email` w access tokenie | tak | **nie** (patrz bug wyżej) |
| Access vs ID token | ten sam string | różne tokeny, różne claimy |

> **Uwaga:** jedyny plik `environment.ts` ma `skipAuth: true` i placeholdery Cognito, a w `angular.json` brak `fileReplacements` — build produkcyjny dostaje lokalną konfigurację. Szczegóły w pliku 09.

## 6. Koszty i pułapki

**Koszty**
- Cognito rozlicza się za MAU (monthly active users). Dla małego dev mieścisz się w darmowym progu (obecnie 10 000 MAU w planach Lite/Essentials — sprawdź aktualny cennik i *feature plan* puli w konsoli, bo moduł nie ustawia go jawnie).
- Prefiks domeny Hosted UI jest darmowy; własna domena wymaga certyfikatu ACM w `us-east-1` (sam certyfikat publiczny jest darmowy).
- Ruch do JWKS jest pomijalny — klucze są cache’owane.

**Pułapki**
- **Mylenie ID i access tokena.** ID token jest dla frontendu (profil użytkownika), access token dla API. Nie wysyłaj ID tokena do API i nie oczekuj `email` w access tokenie.
- **Brak walidacji `iss` / `token_use` / `client_id`** — patrz 3.3.
- **`redirect_mismatch`** — callback URL musi się zgadzać co do znaku (schemat, host, port, ścieżka).
- **Region w URI** — JWKS innej puli/regionu = każdy token odrzucony z `401`.
- **Usunięcie User Pool = utrata użytkowników.** W module nie ma `deletion_protection` ani `lifecycle { prevent_destroy }`.
- **Zmiana grup użytkownika** działa dopiero od następnego tokena — wydany token ma stare `cognito:groups` aż do wygaśnięcia.

## 7. Ćwiczenia

Przygotowanie: `brew install awscli jq` (aws CLI nie jest jeszcze zainstalowane). Serwis uruchom lokalnie: `./scripts/start-local.sh --services` (lub ręcznie z `SPRING_PROFILES_ACTIVE=local`).

**Ćw. 1 — Zdobądź lokalny token i go zdekoduj (darmowe)**

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/dev/token \
  -d 'role=CUSTOMER' -d 'email=ja@test.pl' | jq -r .access_token)
# header i payload (base64url -> JSON)
# JWT używa base64url ('-' i '_' zamiast '+' i '/'), więc najpierw podmieniamy znaki
echo "$TOKEN" | cut -d. -f1 | jq -R 'gsub("-";"+") | gsub("_";"/") | @base64d | fromjson'
echo "$TOKEN" | cut -d. -f2 | jq -R 'gsub("-";"+") | gsub("_";"/") | @base64d | fromjson'
# alternatywa: cut -d. -f2 | tr '_-' '/+' | base64 -d   (może narzekać na brak paddingu '=')
```

Znajdź `kid`, `alg`, `sub`, `cognito:groups`, `exp`. Wklej token na https://jwt.io i porównaj. Zauważ, że brak w nim `iss` i `aud`.

**Ćw. 2 — Autoryzacja: 200 / 401 / 403 (darmowe)**

```bash
curl -i http://localhost:8080/api/stations                          # 200 — permitAll
curl -i http://localhost:8080/api/reservations                      # 401 — brak tokena
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/reservations       # 200
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/admin/stations     # 403
OP=$(curl -s -X POST http://localhost:8080/dev/token -d role=OPERATOR | jq -r .access_token)
curl -i -H "Authorization: Bearer $OP" http://localhost:8080/api/admin/stations        # 200
curl -i -H "Authorization: Bearer $OP" http://localhost:8080/api/reservations          # 403
```

Wyjaśnij każdy wynik, wskazując linię w `SecurityConfig.java`.

**Ćw. 3 — JWKS i fałszerstwo (darmowe)**

```bash
curl -s http://localhost:8080/dev/jwks.json | jq
# podmień payload na OPERATOR, zostaw stary podpis
H=$(echo "$TOKEN" | cut -d. -f1); S=$(echo "$TOKEN" | cut -d. -f3)
P=$(echo '{"sub":"hacker","cognito:groups":["OPERATOR"],"exp":9999999999}' | base64 | tr '+/' '-_' | tr -d '=\n')
curl -i -H "Authorization: Bearer $H.$P.$S" http://localhost:8080/api/admin/stations   # 401
```

Potem zrestartuj serwis i spróbuj starego `$TOKEN` — dlaczego jest odrzucony?

**Ćw. 4 — Przegląd Terraform bez wdrażania (darmowe)**

```bash
brew install terraform
cd infrastructure/environments/dev
terraform init -backend=false && terraform validate
grep -n "cognito" main.tf outputs.tf ../../modules/ecs/main.tf
```

Prześledź, jak `module.cognito.jwks_uri` trafia do zmiennej `COGNITO_JWKS_URI` kontenera i dalej do `application.yml:34`.

**Ćw. 5 — Prawdziwe Cognito na AWS dev (KOSZTUJE: samo Cognito w darmowym progu, ale `terraform apply` całego dev tworzy też płatne NAT/ALB/RDS/ECS)**

Po wdrożeniu dev:

```bash
POOL=$(terraform output -raw cognito_user_pool_id)
aws cognito-idp admin-create-user --user-pool-id "$POOL" --username ja@test.pl \
  --user-attributes Name=email,Value=ja@test.pl Name=email_verified,Value=true --message-action SUPPRESS
aws cognito-idp admin-set-user-password --user-pool-id "$POOL" --username ja@test.pl --password 'Test1234!' --permanent
aws cognito-idp admin-add-user-to-group --user-pool-id "$POOL" --username ja@test.pl --group-name CUSTOMER
curl -s "https://cognito-idp.eu-central-1.amazonaws.com/$POOL/.well-known/openid-configuration" | jq
```

Zaloguj się przez Hosted UI, w DevTools → Application → Session Storage odczytaj `cf_tokens` i porównaj claimy ID i access tokena (szukaj `email`, `aud`, `client_id`, `token_use`). Po ćwiczeniu: `terraform destroy`.

## 8. Pytania kontrolne

**1. Dlaczego app client Angulara ma `generate_secret = false` i co zastępuje sekret?**
<details><summary>Odpowiedź</summary>
SPA działa w przeglądarce — każdy sekret w bundlu JS jest publiczny. Dlatego to public client. Rolę sekretu pełni PKCE: frontend generuje losowy <code>code_verifier</code>, wysyła jego hash (<code>code_challenge</code>, S256) w <code>/oauth2/authorize</code>, a przy <code>/oauth2/token</code> podaje oryginał. Przechwycony <code>code</code> jest bezużyteczny bez verifiera (<code>auth.service.ts:64-106</code>).
</details>

**2. Jak Spring zamienia grupę Cognito na rolę i czemu `hasRole("CUSTOMER")` działa?**
<details><summary>Odpowiedź</summary>
<code>JwtGrantedAuthoritiesConverter</code> czyta claim <code>cognito:groups</code> zamiast domyślnego <code>scope</code> i dodaje prefiks <code>ROLE_</code> (<code>SecurityConfig.java:62-71</code>). Grupa <code>CUSTOMER</code> daje authority <code>ROLE_CUSTOMER</code>, a <code>hasRole("CUSTOMER")</code> sprawdza właśnie <code>ROLE_CUSTOMER</code>.
</details>

**3. Czym różni się ID token od access tokena w Cognito i jaki bug z tego wynika w ChargeFlow?**
<details><summary>Odpowiedź</summary>
ID token (OIDC) opisuje użytkownika dla klienta: ma <code>email</code>, <code>aud</code> = client_id, <code>token_use: id</code>. Access token jest dla API: ma <code>scope</code>, <code>client_id</code>, <code>token_use: access</code>, ale nie ma <code>email</code>. Frontend wysyła access token, a <code>ReservationController.java:36</code> czyta z niego <code>email</code> — na AWS będzie pusty. Lokalnie bug jest niewidoczny, bo dev token zawiera email i jest jednocześnie access i ID tokenem.
</details>

**4. Skąd Spring wie, że token jest prawdziwy, skoro nie odpytuje Cognito przy każdym żądaniu?**
<details><summary>Odpowiedź</summary>
Z podpisu RS256. Spring pobiera (i cache'uje) publiczne klucze z JWKS (<code>jwk-set-uri</code>), wybiera klucz po <code>kid</code> z nagłówka tokena i weryfikuje podpis oraz <code>exp</code>. Klucz prywatny ma tylko Cognito (lokalnie: <code>LocalDevAuthConfig</code>), więc nikt inny nie wyprodukuje poprawnego podpisu.
</details>

**5. Czego brakuje w walidacji JWT w `application.yml` i jakie to ma konsekwencje?**
<details><summary>Odpowiedź</summary>
Jest tylko <code>jwk-set-uri</code>. Brak <code>issuer-uri</code> (walidacja <code>iss</code>) i brak sprawdzania <code>token_use</code>/<code>client_id</code>. W efekcie API przyjmie też ID token oraz tokeny wydane innym app clientom tej puli. Naprawa: dodać <code>issuer-uri</code> i własny <code>OAuth2TokenValidator&lt;Jwt&gt;</code>.
</details>

**6. Czy `./scripts/get-local-token.sh` zadziała w obecnym repo? Dlaczego?**
<details><summary>Odpowiedź</summary>
Nie. <code>infrastructure/environments/local</code> nie tworzy puli Cognito ani nie ma outputów <code>cognito_user_pool_id</code>/<code>cognito_client_id</code>; Cognito w LocalStack wymaga wersji Pro; a flow <code>USER_PASSWORD_AUTH</code> nie jest dozwolony w <code>explicit_auth_flows</code>. Lokalnie należy używać <code>POST /dev/token</code> (profil <code>local</code>).
</details>
