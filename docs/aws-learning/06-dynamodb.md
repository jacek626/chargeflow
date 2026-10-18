# 06 — DynamoDB: historia powiadomień i idempotencja

## 1. Po co to jest

`notification-service` musi pamiętać dwie rzeczy: **„czy już obsłużyłem zdarzenie o tym `eventId`?”**
(żeby nie wysłać kierowcy dwóch takich samych e-maili) oraz **„co wysłałem i z jakim skutkiem?”**
(historia powiadomień). Nie potrzebuje do tego joinów ani transakcji między tabelami — wystarczy
szybki zapis/odczyt po kluczu. To idealny przypadek dla DynamoDB.

Analogia: DynamoDB to **ogromna szafa z szufladami**. Partition key mówi, *do której szuflady*
włożyć kartkę (szafa sama liczy hash z klucza), sort key mówi, *w jakiej kolejności* kartki
leżą w szufladzie. Szybko znajdziesz kartkę, jeśli znasz szufladę. Szukanie „po treści”
oznacza otwieranie wszystkich szuflad (Scan) — wolno i drogo.

## 2. Kluczowe pojęcia AWS

| Pojęcie | Co to jest |
|---|---|
| **Table / Item / Attribute** | Tabela przechowuje itemy (≈ wiersze, max 400 KB); atrybuty poza kluczem są dowolne — brak schematu. |
| **Partition key (hash key)** | Obowiązkowa część klucza głównego. Wartość jest hashowana → wybór partycji fizycznej. |
| **Sort key (range key)** | Opcjonalna druga część klucza. Itemy z tym samym PK są posortowane po SK; pozwala na `Query` z warunkami `begins_with`, `between`, `>`. |
| **GetItem / PutItem / UpdateItem** | Operacje po pełnym kluczu głównym. `PutItem` domyślnie **nadpisuje** item. |
| **Query** | Czyta itemy jednej partycji (podajesz PK, opcjonalnie warunek na SK). Tanie i szybkie. |
| **Scan** | Czyta całą tabelę. Płacisz za wszystko, co przeczytano, nie za to, co zwrócono. |
| **Condition expression** | Warunek sprawdzany atomowo przy zapisie, np. `attribute_not_exists(eventId)`. Niespełniony → `ConditionalCheckFailedException`. Podstawa idempotencji i optimistic locking. |
| **GSI (Global Secondary Index)** | Druga „kopia” danych z innym kluczem (PK/SK), aktualizowana asynchronicznie (eventually consistent). Umożliwia `Query` po innym atrybucie. Każdy zapis do tabeli, który dotyka atrybutów indeksu, kosztuje dodatkowo zapis w GSI. |
| **Projection** | Które atrybuty kopiować do GSI: `KEYS_ONLY`, `INCLUDE`, `ALL`. |
| **Billing mode** | `PAY_PER_REQUEST` (on-demand: płacisz za każde WRU/RRU) vs `PROVISIONED` (rezerwujesz RCU/WCU, opcjonalnie autoscaling). |
| **TTL** | Atrybut z epoch-seconds; DynamoDB sam (z opóźnieniem) kasuje przeterminowane itemy, za darmo. |
| **PITR** | Point-in-time recovery — ciągły backup z możliwością odtworzenia z dowolnej sekundy z ostatnich 35 dni. |
| **Hot partition** | Jedna wartość PK dostaje nieproporcjonalnie dużo ruchu → throttling mimo wolnej pojemności tabeli. |

## 3. Jak to działa w ChargeFlow

### 3.1. Terraform — `infrastructure/modules/dynamodb/main.tf`

```hcl
resource "aws_dynamodb_table" "notifications" {
  name         = "${var.name_prefix}-notifications"   # chargeflow-local-notifications / chargeflow-dev-notifications
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "eventId"
  attribute { name = "eventId"    type = "S" }
  attribute { name = "customerId" type = "S" }
  attribute { name = "createdAt"  type = "S" }
  global_secondary_index {
    name            = "customerId-createdAt-index"
    hash_key        = "customerId"
    range_key       = "createdAt"
    projection_type = "ALL"
  }
}
```

(`infrastructure/modules/dynamodb/main.tf:4-44`)

Co warto zauważyć:

- **Tylko partition key `eventId`, bez sort key.** Każde zdarzenie = jeden item. To dokładnie
  odpowiada głównemu access patternowi: „czy istnieje item o tym `eventId`?”.
- **`attribute {}` deklaruje tylko atrybuty kluczy** (tabeli i indeksów). `eventType` i `status`
  istnieją w itemach, ale nie w Terraformie — to normalne w DynamoDB (zadeklarowanie atrybutu,
  który nie jest kluczem, Terraform/AWS odrzuci).
- **GSI `customerId-createdAt-index`** — przygotowany pod „historię powiadomień klienta,
  posortowaną po czasie”. `projection_type = "ALL"` → zapytanie po indeksie zwróci pełne itemy
  bez dodatkowego `GetItem`.
- **`PAY_PER_REQUEST`** — przy małym, nieregularnym ruchu nie płacisz za bezczynność.
- **Czego nie ma:** `ttl {}`, `point_in_time_recovery {}`, `deletion_protection_enabled`,
  `server_side_encryption` z własnym kluczem KMS (działa domyślne szyfrowanie kluczem AWS-owned),
  `stream_enabled`. Dla dev to OK; dla prod brak PITR i deletion protection to ryzyko,
  a brak TTL oznacza, że tabela rośnie bez końca.

Moduł jest wołany identycznie w `infrastructure/environments/local/main.tf:15-20` i
`infrastructure/environments/dev/main.tf:53-58`. Nazwa tabeli trafia do kontenera jako
`DYNAMODB_NOTIFICATIONS_TABLE` (`infrastructure/modules/ecs/main.tf:281`).

**IAM** (`infrastructure/modules/ecs/main.tf:154-163`): `dynamodb:PutItem`, `GetItem`, `Query`,
`UpdateItem` na `arn:aws:dynamodb:<region>:<konto>:table/*`. Uwaga: wildcard na *wszystkie* tabele
konta, a moduł ma gotowy output `notifications_table_arn` (`infrastructure/modules/dynamodb/outputs.tf:8-11`),
który nie jest użyty. Do `Query` po GSI potrzebny byłby zasób `.../table/<nazwa>/index/*` —
wildcard `table/*` go pokrywa.

### 3.2. Java — klient i repozytorium

Klient: **niskopoziomowy `DynamoDbClient` z AWS SDK v2**, nie Enhanced Client (`@DynamoDbBean`)
ani Spring Data. Bean jest tworzony zawsze (bez `@ConditionalOnProperty`), z opcjonalnym
`endpointOverride` dla LocalStack
(`notification-service/src/main/java/pl/chargeflow/notification/config/DynamoDbConfig.java:17-28`).
Nazwa tabeli: `chargeflow.dynamodb.table-name`, domyślnie `chargeflow-local-notifications`
(`notification-service/src/main/resources/application.yml:15-16`).

Model to zwykły record
(`notification-service/src/main/java/pl/chargeflow/notification/notification/NotificationRecord.java:6-15`):
`eventId, customerId, eventType, status (PENDING|SENT|FAILED), createdAt`.

**Idempotentny zapis — warunkowy `PutItem`**
(`notification-service/src/main/java/pl/chargeflow/notification/notification/NotificationRepository.java:35-53`):

```java
dynamoDb.putItem(PutItemRequest.builder()
        .tableName(tableName)
        .item(Map.of(
                "eventId",    str(record.eventId().toString()),
                "customerId", str(record.customerId()),
                "eventType",  str(record.eventType()),
                "status",     str(record.status().name()),      // PENDING
                "createdAt",  str(record.createdAt().toString())))
        .conditionExpression("attribute_not_exists(eventId)")
        .build());
return true;
} catch (ConditionalCheckFailedException e) {
    return false;     // ten eventId już był → duplikat
}
```

Dlaczego warunek, a nie „najpierw `GetItem`, potem `PutItem`”? Bo get-then-put to **race condition**:
dwie instancje serwisu mogą jednocześnie odebrać tę samą wiadomość (np. po wygaśnięciu visibility
timeout), obie zobaczą „brak itemu” i obie wyślą e-mail. Condition expression jest sprawdzany
**atomowo po stronie DynamoDB** — wygra dokładnie jeden zapis. Bez warunku `PutItem` po cichu
nadpisałby istniejący item.

**Zmiana statusu — `UpdateItem`** (`NotificationRepository.java:55-63`):

```java
.key(Map.of("eventId", str(eventId.toString())))
.updateExpression("SET #s = :status")
.expressionAttributeNames(Map.of("#s", "status"))
```

`#s` jest konieczne, bo `status` to **słowo zarezerwowane** w DynamoDB — użyte wprost
w wyrażeniu dałoby `ValidationException`.

Przepływ w handlerze (np.
`notification-service/src/main/java/pl/chargeflow/notification/consumer/handler/ReservationCreatedHandler.java:44-65`):
`saveIfNew(PENDING)` → e-mail przez SES → `updateStatus(SENT)` albo `updateStatus(FAILED)`.
Jako `customerId` brane jest `payload.customerId`, a gdy go brak — literał `"?"` (linia 44).

### 3.3. Access patterns

| # | Wzorzec | Operacja | Czy jest w kodzie? |
|---|---|---|---|
| 1 | Zarejestruj zdarzenie, jeśli nowe (idempotencja) | `PutItem` + `attribute_not_exists(eventId)` | tak |
| 2 | Ustaw status powiadomienia | `UpdateItem` po `eventId` | tak |
| 3 | Historia powiadomień klienta, od najnowszych | `Query` na GSI `customerId = :c`, `ScanIndexForward=false` | **nie** — indeks istnieje, ale nikt go nie odpytuje |

Uwaga: GSI i uprawnienie `dynamodb:Query` są przygotowane „na zapas”. Spec wspomina
„Notification history” (`docs/product-specs/chargeflow-spec.md:76`), ale w `notification-service`
nie ma endpointu ani zapytania, które z indeksu korzysta (security config wręcz blokuje wszystkie
endpointy poza health — `notification-service/src/main/java/pl/chargeflow/notification/config/SecurityConfig.java:22-25`).
Mimo to **płacisz za GSI przy każdym zapisie**.

### 3.4. Projekt kluczy — dlaczego tak

- **`eventId` (UUID) jako PK tabeli** — wartości losowe, idealnie równomierny rozkład po partycjach,
  zero ryzyka hot partition. Klucz jest naturalnym „kluczem idempotencji” — pochodzi z wiersza
  outbox producenta i jest stały przy ponownych publikacjach.
- **`customerId` jako PK indeksu, `createdAt` jako SK** — klasyczny wzorzec „jeden klient = jedna
  partycja, w środku oś czasu”. `Query` z `customerId` i np. `createdAt > :od` zwraca posortowaną
  historię bez Scan.
- `createdAt` jest stringiem ISO-8601 — sortowanie leksykograficzne ≈ chronologiczne.
  Uwaga: `Instant.toString()` pomija zerowe ułamki sekund (`...17:00:00Z` vs `...17:00:00.5Z`),
  a `'.' < 'Z'` w ASCII, więc w obrębie tej samej sekundy kolejność może się odwrócić.
  Stała szerokość (np. zawsze milisekundy) by to naprawiła.
- Fallback `"?"` dla brakującego `customerId` tworzy jedną wspólną partycję w GSI dla wszystkich
  „bezpańskich” zdarzeń — przy dużej skali potencjalny hot key.

## 4. Diagram

```
 SQS message (eventId=E1, payload.customerId=C7)
        │
        ▼
 Handler.handle()
        │ 1. PutItem {eventId=E1, customerId=C7, eventType, status=PENDING, createdAt}
        │    ConditionExpression: attribute_not_exists(eventId)
        ▼
 ┌───────────────────── tabela chargeflow-<env>-notifications ─────────────────────┐
 │ PK: eventId                                                                      │
 │  E1 │ C7 │ RESERVATION_CREATED │ PENDING→SENT │ 2026-09-14T17:00:00Z            │
 │  E2 │ C7 │ CHARGING_STARTED    │ SENT         │ 2026-09-14T17:03:00Z            │
 │  E3 │ C9 │ RESERVATION_CREATED │ FAILED       │ 2026-09-14T17:05:00Z            │
 └───────────────┬──────────────────────────────────────────────────────────────────┘
        │        │ asynchroniczna replikacja (projection ALL)
        │        ▼
        │  ┌──────────── GSI customerId-createdAt-index ────────────┐
        │  │ PK: customerId   SK: createdAt                          │
        │  │  C7 │ 2026-09-14T17:00:00Z │ E1 ...                     │
        │  │  C7 │ 2026-09-14T17:03:00Z │ E2 ...   ← Query(C7)       │
        │  │  C9 │ 2026-09-14T17:05:00Z │ E3 ...     (nieużywane)    │
        │  └─────────────────────────────────────────────────────────┘
        │
   OK ──┴─► SES ─► UpdateItem SET #s = SENT   (lub FAILED przy błędzie)
   ConditionalCheckFailedException ─► "duplikat", return (wiadomość zostanie usunięta z SQS)
```

## 5. Lokalnie vs AWS (dev)

| Aspekt | LocalStack | AWS dev |
|---|---|---|
| Tabela | tworzona przez `terraform apply` w `environments/local` (nazwa `chargeflow-local-notifications`, jednocześnie domyślna w `application.yml`) | `chargeflow-dev-notifications`, nazwa przez env `DYNAMODB_NOTIFICATIONS_TABLE` |
| Endpoint | `AWS_ENDPOINT_URL=http://localhost:4566` | domyślny regionalny (w VPC bez VPC endpointu ruch idzie przez NAT) |
| Billing / throttling | brak opłat, brak realnego throttlingu | on-demand: płacisz za WRU/RRU; throttling możliwy przy skokach > 2× poprzedniego szczytu |
| Spójność GSI | praktycznie natychmiastowa | eventual — świeży item może nie być od razu widoczny w `Query` po indeksie |
| IAM | nieegzekwowane domyślnie | rola zadania ECS, wildcard `table/*` |
| Dane | znikają po restarcie LocalStack (brak persystencji w `docker-compose.yml`) | trwałe (ale bez PITR) |

Notification-service **zawsze** tworzy `DynamoDbClient` — bez działającej tabeli (lokalnie:
bez LocalStack) obsługa każdego zdarzenia skończy się wyjątkiem i retry z SQS.

## 6. Koszty i pułapki

**Koszty.** On-demand: każdy zapis ≤ 1 KB = 1 WRU, odczyt ≤ 4 KB = 0,5 RRU (eventually) / 1 RRU (strong).
Jedno powiadomienie to tutaj: `PutItem` (1 WRU) + zapis do GSI (1 WRU) + `UpdateItem` statusu
(1 WRU) + aktualizacja GSI, bo przy `projection ALL` zmiana `status` zmienia też item w indeksie
(1 WRU) ≈ **4 WRU na e-mail**, z czego połowa idzie na nieużywany indeks. Duplikat
(`ConditionalCheckFailed`) też zużywa WRU. Storage ~ $0.25/GB-mies. (sprawdź aktualny cennik dla
eu-central-1). Przy skali V1 to grosze; liczy się nawyk liczenia.

**Pułapki:**

1. **`PutItem` bez warunku nadpisuje** — najczęstszy błąd przy próbie „idempotencji”.
2. **Get-then-put zamiast condition expression** — race condition przy równoległych konsumentach.
3. **Słowa zarezerwowane** (`status`, `name`, `date`, `count`...) — zawsze przez
   `ExpressionAttributeNames`.
4. **Scan w kodzie produkcyjnym** — koszt rośnie liniowo z tabelą; projektuj klucze pod `Query`.
5. **Hot partition** — PK o niskiej kardynalności (np. `status`, `eventType`, `"?"`) jako klucz
   indeksu/tabeli. Tu `eventId` jest bezpieczny; GSI po `customerId` jest OK, dopóki nie ma
   „super-klienta” (np. flotowego) generującego ogromny ruch.
6. **GSI kosztuje przy każdym zapisie** — indeks „na przyszłość” to realne pieniądze.
7. **Brak TTL** — rekordy idempotencji potrzebne są tylko tak długo, jak długo możliwe są
   duplikaty (retencja SQS = 1 dzień). Atrybut `expiresAt` + TTL ograniczyłby rozmiar tabeli,
   jeśli historia nie musi być wieczna.
8. **Idempotencja zbyt „gorliwa”** — tu rekord `FAILED` blokuje ponowną próbę wysyłki
   (szczegóły w [05-messaging-sns-sqs.md](05-messaging-sns-sqs.md), sekcja 3.3). Typowe rozwiązanie:
   warunek `attribute_not_exists(eventId) OR #s = :failed` albo osobny licznik prób.
9. **Brak PITR / deletion protection** — `terraform destroy` lub pomyłka kasuje historię bezpowrotnie.

## 7. Ćwiczenia

```bash
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=eu-central-1
alias awsl='aws --endpoint-url=http://localhost:4566'
T=chargeflow-local-notifications
```

**Ćw. 1 — anatomia tabeli.**

```bash
awsl dynamodb describe-table --table-name $T \
  --query 'Table.{keys:KeySchema,billing:BillingModeSummary,gsi:GlobalSecondaryIndexes[].{n:IndexName,k:KeySchema,p:Projection}}'
awsl dynamodb describe-time-to-live --table-name $T          # TTL: DISABLED
awsl dynamodb describe-continuous-backups --table-name $T    # PITR: DISABLED
```

**Ćw. 2 — zobacz dane po przepływie.** Utwórz rezerwację przez API albo opublikuj zdarzenie na SNS
(przykład w [05](05-messaging-sns-sqs.md), ćw. 2) przy działającym notification-service, potem:

```bash
awsl dynamodb scan --table-name $T --max-items 20
```

Lokalnie `SES_ENABLED=false` → `NoOpEmailService`, więc status powinien być `SENT`.

**Ćw. 3 — ręczna idempotencja.** Wykonaj dwa razy:

```bash
awsl dynamodb put-item --table-name $T \
  --item '{"eventId":{"S":"ex-1"},"customerId":{"S":"cust-1"},"eventType":{"S":"TEST"},"status":{"S":"PENDING"},"createdAt":{"S":"2026-10-18T10:00:00Z"}}' \
  --condition-expression 'attribute_not_exists(eventId)'
```

Drugie wywołanie zwróci `ConditionalCheckFailedException` — dokładnie to łapie `NotificationRepository`.
Usuń `--condition-expression` i sprawdź, że wtedy item jest po cichu nadpisywany.

**Ćw. 4 — Query po GSI (wzorzec historii, którego brakuje w kodzie).**

```bash
awsl dynamodb query --table-name $T --index-name customerId-createdAt-index \
  --key-condition-expression 'customerId = :c' \
  --expression-attribute-values '{":c":{"S":"cust-1"}}' \
  --no-scan-index-forward
```

Spróbuj dodać `AND createdAt > :d`. Potem spróbuj zrobić `query` po `status` — dlaczego się nie da?

**Ćw. 5 — słowo zarezerwowane.**

```bash
awsl dynamodb update-item --table-name $T --key '{"eventId":{"S":"ex-1"}}' \
  --update-expression 'SET status = :s' --expression-attribute-values '{":s":{"S":"SENT"}}'
```

Zobacz błąd, a potem popraw na `SET #s = :s` z `--expression-attribute-names '{"#s":"status"}'`.
(LocalStack może być mniej restrykcyjny niż AWS — jeśli nie zobaczysz błędu, zapamiętaj, że w AWS on wystąpi.)

## 8. Pytania kontrolne

1. Dlaczego idempotencja korzysta z `conditionExpression`, a nie z `GetItem` przed `PutItem`?
   <details><summary>Odpowiedź</summary>Bo get-then-put nie jest atomowe: dwa równoległe konsumenty mogą oba zobaczyć brak itemu i oba wysłać e-mail. Condition expression jest sprawdzany atomowo przez DynamoDB podczas zapisu — tylko jeden `PutItem` się powiedzie, drugi dostanie `ConditionalCheckFailedException`.</details>

2. Czemu `eventId` jest dobrym partition key, a `status` byłby złym?
   <details><summary>Odpowiedź</summary>`eventId` to losowy UUID o ogromnej kardynalności — ruch rozkłada się równomiernie po partycjach. `status` ma 3 wartości, więc cały ruch trafiałby do 3 kluczy → hot partitions i throttling, a dodatkowo nie dałoby się szybko znaleźć konkretnego zdarzenia.</details>

3. Do czego służy GSI `customerId-createdAt-index` i ile kosztuje, skoro nic go nie czyta?
   <details><summary>Odpowiedź</summary>Do zapytania „historia powiadomień klienta posortowana po czasie” (`Query` po `customerId` z sortowaniem po `createdAt`). Nikt go nie odpytuje, ale każdy zapis do tabeli (PutItem i UpdateItem statusu, bo projection ALL) generuje dodatkowy zapis do indeksu — mniej więcej podwaja koszt zapisów.</details>

4. Dlaczego w `UpdateItem` jest `#s` zamiast `status`?
   <details><summary>Odpowiedź</summary>`status` jest słowem zarezerwowanym DynamoDB. W wyrażeniach trzeba go podstawić przez `ExpressionAttributeNames`, inaczej request kończy się `ValidationException`.</details>

5. Czego brakuje w module Terraform przed produkcją i po co?
   <details><summary>Odpowiedź</summary>PITR (odtworzenie po błędzie/usunięciu danych), `deletion_protection_enabled` (ochrona przed przypadkowym destroy), TTL (kontrola wzrostu tabeli — rekordy idempotencji są potrzebne tylko przez okres możliwych duplikatów), ewentualnie zawężenie IAM z `table/*` do ARN tej tabeli.</details>
