# 05 — Messaging: SNS + SQS + Transactional Outbox

## 1. Po co to jest

Charging Session Service nie powinien wiedzieć, kto i jak reaguje na to, że kierowca zarezerwował
ładowarkę. Ma tylko **ogłosić fakt**: „rezerwacja X została utworzona”. Reszta (e-mail, w przyszłości
analityka, płatności) to sprawa odbiorców.

Analogia: **SNS to tablica ogłoszeń** (kto przypiął ogłoszenie, nie wie, kto je przeczyta),
a **SQS to skrzynka pocztowa** konkretnego odbiorcy — list czeka w niej, nawet jeśli odbiorca
jest akurat na urlopie (serwis leży). **DLQ** to szuflada „listy, których nie dało się doręczyć”.
**Transactional Outbox** to zeszyt „do wysłania”, do którego wpisujemy list w tej samej chwili,
w której zapisujemy dane w bazie — więc nie ma szans, że zapiszemy rezerwację, a o liście zapomnimy.

W ChargeFlow to serce architektury: `charging-session-service` → Outbox → SNS → SQS → `notification-service`.

## 2. Kluczowe pojęcia AWS

| Pojęcie | Co to jest |
|---|---|
| **SNS topic** | Kanał pub/sub. `Publish` wysyła wiadomość do *wszystkich* subskrypcji. Brak przechowywania — jeśli nikt nie subskrybuje, wiadomość przepada. |
| **Subscription** | Podpięcie odbiorcy (tu: kolejki SQS) do topicu. Może mieć **filter policy** i **raw message delivery**. |
| **Fan-out** | Jeden publish → wiele kolejek. Każdy nowy konsument dostaje własną kolejkę i subskrypcję; producent się nie zmienia. |
| **Raw message delivery** | Domyślnie SNS opakowuje treść w JSON (`{"Type":"Notification","Message":"...", ...}`). Z `raw_message_delivery = true` do SQS trafia dokładnie to, co opublikowano. |
| **Filter policy** | Reguła na subskrypcji (np. po `MessageAttributes.eventType`), która przepuszcza tylko część wiadomości. |
| **SQS standard queue** | Kolejka *at-least-once*, bez gwarancji kolejności. Wiadomość może przyjść dwa razy. |
| **Visibility timeout** | Po `ReceiveMessage` wiadomość jest „niewidzialna” dla innych przez N sekund. Jeśli konsument jej nie usunie (`DeleteMessage`) — wraca do kolejki. To jest mechanizm retry. |
| **Receive count** | Ile razy wiadomość została odebrana (`ApproximateReceiveCount`). |
| **Redrive policy / DLQ** | `maxReceiveCount` — po przekroczeniu tylu odbiorów SQS przenosi wiadomość do Dead Letter Queue. |
| **Long polling** | `WaitTimeSeconds` do 20 s — `ReceiveMessage` czeka na wiadomości zamiast od razu wracać pusty. Mniej pustych (płatnych) requestów. |
| **Retention** | Jak długo nieodebrana wiadomość żyje w kolejce (60 s – 14 dni). |
| **Queue policy** | Resource-based policy kolejki — tu: pozwala *usłudze SNS* robić `sqs:SendMessage`. |
| **At-least-once** | Gwarancja „dostarczę co najmniej raz”. Konsekwencja: konsument **musi być idempotentny**. |

## 3. Jak to działa w ChargeFlow

### 3.1. Terraform — `infrastructure/modules/messaging`

Nazwy powstają z `name_prefix = "${var.project_name}-${var.environment}"`
(`infrastructure/environments/dev/providers.tf:22`), więc lokalnie mamy:
`chargeflow-local-domain-events`, `chargeflow-local-notifications`, `chargeflow-local-notifications-dlq`
(w dev: `chargeflow-dev-...`).

**Topic** — jeden na wszystkie zdarzenia domenowe (`infrastructure/modules/messaging/main.tf:4`):

```hcl
resource "aws_sns_topic" "domain_events" {
  name = "${var.name_prefix}-domain-events"
}
```

**DLQ i kolejka główna** (`infrastructure/modules/messaging/main.tf:12-30`):

```hcl
resource "aws_sqs_queue" "notifications" {
  name                       = "${var.name_prefix}-notifications"
  visibility_timeout_seconds = var.sqs_visibility_timeout_seconds   # 30
  message_retention_seconds  = var.message_retention_seconds        # 86400 = 1 dzień
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.notifications_dlq.arn
    maxReceiveCount     = var.dlq_max_receive_count                 # 3
  })
}
```

Wartości domyślne: `infrastructure/modules/messaging/variables.tf:16-35`. Środowiska `local` i `dev`
ich nie nadpisują (`infrastructure/environments/dev/main.tf:35-40`).

**Queue policy** (`infrastructure/modules/messaging/main.tf:34-58`) — SNS to *usługa*, a nie rola IAM,
więc to kolejka musi jej pozwolić pisać. Warunek `aws:SourceArn` ogranicza to do naszego topicu
(bez niego dowolny topic w AWS, znający ARN kolejki, mógłby do niej pisać — tzw. *confused deputy*).

**Subskrypcja** (`infrastructure/modules/messaging/main.tf:63-71`):

```hcl
resource "aws_sns_topic_subscription" "notifications" {
  topic_arn            = aws_sns_topic.domain_events.arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.notifications.arn
  raw_message_delivery = true
}
```

`raw_message_delivery = true` jest kluczowe dla kodu Javy: konsument parsuje `message.body()`
wprost jako kopertę zdarzenia (`IncomingEvent`). Bez raw delivery body byłoby kopertą SNS,
a właściwe zdarzenie siedziałoby jako *string* w polu `Message` — parsowanie by się wysypało.

Uwaga: w kodzie **nie ma filter policy**, a producenci nie ustawiają `MessageAttributes`
(patrz 3.2), więc filtrowanie po typie zdarzenia nie byłoby nawet możliwe bez zmian w publisherach.

**Uprawnienia IAM** (`infrastructure/modules/ecs/main.tf:133-150`): rola zadania ECS ma
`sns:Publish` na topic oraz `sqs:ReceiveMessage`, `sqs:DeleteMessage`, `sqs:GetQueueAttributes`
na `arn:aws:sqs:<region>:<konto>:*`. Uwaga: to **jedna rola współdzielona przez oba serwisy**
(`task_role_arn` w liniach 158 i 212), a zasób SQS to wildcard na wszystkie kolejki konta —
działa, ale łamie zasadę least privilege.

### 3.2. Producent — Transactional Outbox w `charging-session-service`

**Problem dual write.** Gdyby serwis robił `save(reservation)` i zaraz `sns.publish(...)`, to:
commit się udaje, a publish pada → zdarzenie zgubione; albo publish się udaje, a commit pada →
e-mail o rezerwacji, której nie ma. Nie da się zrobić jednej transakcji obejmującej Postgresa i SNS.

**Rozwiązanie:** w tej samej transakcji co dane biznesowe zapisujemy wiersz w tabeli `outbox_event`,
a osobny poller publikuje go do SNS później.

Tabela (`charging-session-service/src/main/resources/db/migration/V5__create_outbox_event.sql:1-15`):

```sql
CREATE TABLE outbox_event (
    id UUID NOT NULL DEFAULT gen_random_uuid(),  -- staje się eventId
    aggregate_id UUID NOT NULL, aggregate_type VARCHAR(20) NOT NULL,
    event_type VARCHAR(30) NOT NULL, payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL, published_at TIMESTAMPTZ,
    status VARCHAR(15) NOT NULL DEFAULT 'NEW',   -- NEW | PUBLISHED
    ...
);
CREATE INDEX idx_outbox_event_new ON outbox_event (created_at) WHERE status = 'NEW';
```

Indeks częściowy obejmuje tylko nieopublikowane wiersze — mały i szybki nawet gdy tabela urośnie.

Zapis w transakcji biznesowej, np. `ReservationService.createReservation`
(`charging-session-service/src/main/java/pl/chargeflow/session/reservation/ReservationService.java:41-53`):
metoda jest `@Transactional`, robi `reservationRepository.save(...)`, a potem
`outboxEventService.saveReservationCreated(saved)`. `OutboxEventService` serializuje payload
Jacksonem do JSON-a i zapisuje encję `OutboxEvent`
(`charging-session-service/src/main/java/pl/chargeflow/session/outbox/OutboxEventService.java:89-98`).

Uwaga: atomowość outboxa zależy od tego, czy transakcja naprawdę obejmuje oba zapisy.
W `ReservationExpirationJob` metoda `@Scheduled expireReservations()` woła **na `this`**
metodę `@Transactional expireReservationsBefore(...)`
(`charging-session-service/src/main/java/pl/chargeflow/session/reservation/ReservationExpirationJob.java:34-52`).
Przy domyślnych proxy Springa takie self-invocation omija proxy, więc adnotacja `@Transactional`
nie zadziała przy wywołaniu z harmonogramu (w testach, które wołają ją bezpośrednio, problem
może być niewidoczny). Warto to zweryfikować — to typowa pułapka przy Outboxie.

Uwaga: payloady zawierają pole `customerEmail` (np. `OutboxEventService.java:33-37`), którego **nie ma**
w kontrakcie `docs/contracts/events/README.md`. Kod konsumenta na nim polega (adres odbiorcy e-maila),
więc to kontrakt jest nieaktualny względem kodu.

**Poller** (`charging-session-service/src/main/java/pl/chargeflow/session/outbox/OutboxPoller.java:25-36`):

```java
@Scheduled(fixedDelayString = "${chargeflow.outbox.poll-interval-ms:5000}")
@Transactional
public void pollAndPublish() {
    List<OutboxEvent> events = repository.findUnpublishedBatch(BATCH_SIZE); // 100
    for (OutboxEvent event : events) {
        publisher.publish(event);
        event.markPublished();          // status=PUBLISHED, published_at=now
    }
}
```

Zapytanie (`OutboxEventRepository.java:13-20`) to `SELECT ... WHERE status='NEW' ORDER BY created_at
LIMIT :limit FOR UPDATE SKIP LOCKED` — kilka instancji serwisu (kilka tasków ECS) nie weźmie
tych samych wierszy.

**Publisher SNS** (`charging-session-service/src/main/java/pl/chargeflow/session/outbox/SnsDomainEventPublisher.java:33-60`)
buduje kopertę zgodną z kontraktem:

```java
envelope.put("eventId", event.getId().toString());      // id wiersza outbox
envelope.put("eventType", event.getEventType().name());
envelope.put("eventVersion", VERSION);                   // "1.0"
envelope.put("occurredAt", event.getCreatedAt().toString());
envelope.put("aggregateId", event.getAggregateId().toString());
envelope.set("payload", payloadNode);
snsClient.publish(PublishRequest.builder().topicArn(topicArn).message(body).build());
```

Ważne: `eventId` = klucz główny wiersza outbox, więc ponowna publikacja tego samego wiersza
niesie **ten sam** `eventId` — na tym opiera się idempotencja konsumenta.

Przełącznik: `chargeflow.sns.enabled` (`charging-session-service/src/main/resources/application.yml:42-44`).
Gdy `false` (domyślnie), działa `LoggingDomainEventPublisher` — tylko loguje
(`LoggingDomainEventPublisher.java:10`). Klient SDK v2 ma opcjonalny `endpointOverride`
z `AWS_ENDPOINT_URL` — tak samo kod gada z LocalStack i z AWS (`SnsConfig.java:19-30`).

**Dlaczego to daje at-least-once, a nie exactly-once:** SNS publish dzieje się *przed* commitem
transakcji pollera. Jeśli proces padnie po publish, a przed commitem — wiersz zostaje `NEW`
i zostanie opublikowany ponownie. Podobnie, jeśli w batchu 100 zdarzeń publish #50 rzuci wyjątek,
cała transakcja jest wycofywana: zdarzenia 1–49 już poszły do SNS, ale w bazie dalej są `NEW` →
pójdą jeszcze raz. To jest świadomy kompromis wzorca Outbox — duplikaty zamiast utraty.

### 3.3. Konsument — `notification-service`

Konfiguracja (`notification-service/src/main/resources/application.yml:20-26`): `max-messages: 10`,
`visibility-timeout-seconds: 30`, `wait-time-seconds: 20`, `poll-interval-ms: 100`.

Pętla (`notification-service/src/main/java/pl/chargeflow/notification/consumer/SqsEventConsumer.java:49-79`):

```java
@Scheduled(fixedDelayString = "${chargeflow.sqs.poll-interval-ms:100}")
public void poll() {
    messages = sqsClient.receiveMessage(... .maxNumberOfMessages(10)
            .visibilityTimeout(30).waitTimeSeconds(20) ...).messages();
    for (Message m : messages) processMessage(m);
}
private void processMessage(Message message) {
    try {
        IncomingEvent event = objectMapper.readValue(message.body(), IncomingEvent.class);
        router.route(event);
        deleteMessage(message);                // "ack" = DeleteMessage
    } catch (Exception e) {
        // Do not delete — SQS will redeliver after visibility timeout expires
    }
}
```

- To ręczny poller na „gołym” SDK v2, nie Spring Cloud AWS `@SqsListener`.
- **Ack = `DeleteMessage`**. Nie ma `ChangeMessageVisibility` ani backoffu — retry to po prostu
  wygaśnięcie visibility timeout (30 s; konsument nadpisuje wartość z kolejki tą samą liczbą).
- Wiadomości z batcha są przetwarzane **sekwencyjnie** w jednym wątku.

`DomainEventRouter` (`notification-service/src/main/java/pl/chargeflow/notification/consumer/DomainEventRouter.java:27-41`)
wybiera handler po `eventType`. Brak handlera (np. `CHARGING_FAILED`, `NOTIFICATION_SENT`) → log
debug i powrót bez wyjątku → wiadomość zostaje **usunięta**. Liczniki Micrometer
`chargeflow.notifications.sent/failed`.

**Idempotencja po `eventId`** — w każdym handlerze, np.
`notification-service/src/main/java/pl/chargeflow/notification/consumer/handler/ReservationCreatedHandler.java:43-68`:

```java
if (!repository.saveIfNew(record)) {   // PutItem z attribute_not_exists(eventId)
    return;                            // duplikat → nic nie rób (wiadomość zostanie usunięta)
}
try {
    email.sendReservationCreated(...);
    repository.updateStatus(eventId, SENT);
    publisher.publishSent(...);
} catch (Exception e) {
    repository.updateStatus(eventId, FAILED);
    publisher.publishFailed(..., "EMAIL_SEND_FAILED");
    throw new RuntimeException(...);  // → brak DeleteMessage → redelivery
}
```

Szczegóły warunkowego zapisu — w [06-dynamodb.md](06-dynamodb.md).

> **Uwaga — retry e-maila w praktyce nie działa.** Przy błędzie SES rekord w DynamoDB już istnieje
> (status `FAILED`). Po 30 s SQS dostarcza wiadomość ponownie, `saveIfNew` zwraca `false`
> („duplikat”), handler kończy się bez wyjątku i wiadomość jest **usuwana**. E-mail nie jest
> ponawiany, a wiadomość nie trafia do DLQ. Do DLQ trafią więc głównie wiadomości, które padają
> *przed* `saveIfNew` — np. niepoprawny JSON albo nieznana wartość `eventType` (enum) w kopercie,
> czy chwilowy błąd samego DynamoDB. Kontrakt mówi, że `NOTIFICATION_FAILED` jest publikowany
> „after all retries” — kod publikuje go przy pierwszej porażce.

### 3.4. Zdarzenia wynikowe: NOTIFICATION_SENT / NOTIFICATION_FAILED

`SnsNotificationResultPublisher`
(`notification-service/src/main/java/pl/chargeflow/notification/publisher/SnsNotificationResultPublisher.java:51-69`)
publikuje kopertę z **losowym** `eventId`, a `aggregateId` = `eventId` zdarzenia wyzwalającego
(handlery przekazują `event.eventId()` jako `notificationId`). Błąd publikacji jest tylko logowany
(połykany) — nie ma tu outboxa, więc te zdarzenia mogą zginąć.

Dokąd idą? W ECS `SNS_NOTIFICATION_TOPIC_ARN = var.sns_topic_arn`
(`infrastructure/modules/ecs/main.tf:284-287`) — czyli **ten sam topic `domain-events`**.
Skoro subskrypcja nie ma filter policy, `NOTIFICATION_SENT` wraca do kolejki `notifications`,
konsument go odbiera, router nie ma handlera, wiadomość jest usuwana. Pętli nieskończonej nie ma,
ale każdy e-mail generuje dodatkową, bezużyteczną wiadomość w kolejce (i requesty SQS).
Lokalnie `scripts/start-local.sh` nie ustawia `SNS_ENABLED` dla notification-service, więc działa
`LoggingNotificationResultPublisher` i efekt nie występuje.

## 4. Diagram

```
 charging-session-service                                   notification-service
 ┌──────────────────────────────────────┐
 │ @Transactional createReservation()   │
 │  ├─ INSERT reservation      ┐ jedna  │
 │  └─ INSERT outbox_event NEW ┘ trans. │
 │                                      │
 │ OutboxPoller co 5 s (@Transactional) │
 │  SELECT ... FOR UPDATE SKIP LOCKED   │
 │  sns.publish(envelope) ──────────────┼──┐
 │  UPDATE status=PUBLISHED             │  │
 └──────────────────────────────────────┘  │
                                           ▼
                       ┌────────────────────────────────┐
                       │ SNS  chargeflow-<env>-domain-events │◄──────────────┐
                       └───────────────┬────────────────┘                │
                 subscription (sqs, raw_message_delivery=true,           │
                                no filter policy)                        │
                                       ▼                                 │
              ┌──────────────────────────────────────┐                   │
              │ SQS chargeflow-<env>-notifications   │                   │
              │  visibility 30 s, retention 1 dzień  │                   │
              └───────┬───────────────────▲──────────┘                   │
     ReceiveMessage   │  (long poll 20 s) │ brak Delete →                │
     max 10           │                   │ wraca po 30 s                │
                      ▼                   │                              │
          ┌───────────────────────────────┴──────┐                       │
          │ SqsEventConsumer → DomainEventRouter │                       │
          │  → Handler: saveIfNew (DynamoDB)     │                       │
          │           → SES                      │                       │
          │           → publishSent/Failed ──────┼───────────────────────┘
          │  OK → DeleteMessage                  │   (dev: ten sam topic!)
          └──────────────────────────────────────┘
                      │ receiveCount > 3
                      ▼
              ┌──────────────────────────────────────┐
              │ SQS chargeflow-<env>-notifications-dlq│  (nikt jej nie czyta,
              │  retention 1 dzień                    │   brak alarmu)
              └──────────────────────────────────────┘
```

## 5. Lokalnie vs AWS (dev)

| Aspekt | Lokalnie (LocalStack) | AWS dev |
|---|---|---|
| Tworzenie zasobów | `terraform apply` w `infrastructure/environments/local` (robi to `scripts/start-local.sh`) | `infrastructure/environments/dev` |
| Endpoint | `AWS_ENDPOINT_URL=http://localhost:4566` → `endpointOverride` w `SnsConfig`/`SqsConfig` | brak override, domyślne endpointy regionu |
| Credentials | `test`/`test` (LocalStack nie sprawdza) | rola zadania ECS (`DefaultCredentialsProvider`) |
| Konto w ARN | `000000000000` | prawdziwe ID konta |
| Queue policy / IAM | LocalStack domyślnie **nie egzekwuje** IAM ani resource policies — błąd w policy lokalnie nie wyjdzie | egzekwowane; zła `aws:SourceArn` = wiadomości nie dochodzą, bez żadnego błędu u producenta |
| Result events | notification-service bez `SNS_ENABLED` → tylko log | publikowane na topic domain-events |
| Wydajność/latencja | natychmiastowa | realna, ale ms |

Uwaga: `docs/architecture/messaging.md` ma status PLACEHOLDER — ten plik opisuje stan z kodu.

## 6. Koszty i pułapki

**Koszty** (rząd wielkości, sprawdź aktualny cennik): SNS ok. $0.50 / mln publish, dostarczenie
SNS→SQS jest darmowe; SQS ok. $0.40 / mln requestów (pierwszy milion miesięcznie w free tier),
każde 64 KB to osobny request. **Każdy `ReceiveMessage` jest płatny, także pusty** — dlatego long
polling 20 s jest ważny: jeden konsument to ok. 3 requesty/min ≈ 130 tys./mies. Bez long pollingu
przy `poll-interval-ms: 100` byłoby to ~26 mln requestów miesięcznie na jedną instancję.

**Pułapki:**

1. **Poison message** — wiadomość, której nie da się przetworzyć nigdy (zły JSON, nieznany
   `eventType`). Bez DLQ krąży do końca retention. Tu: po 3 odbiorach (~90 s) ląduje w DLQ.
2. **Visibility timeout < czas przetwarzania.** Batch 10 wiadomości jest przetwarzany sekwencyjnie;
   jeśli SES/DynamoDB zwolnią i batch potrwa > 30 s, końcówka batcha stanie się znów widoczna
   i inna instancja ją weźmie → duplikaty. Ratuje nas idempotencja, ale wiadomość może też
   dostać zawyżony receive count.
3. **DLQ retention.** Dla kolejek standard wiek wiadomości w DLQ liczy się od *pierwotnego*
   wysłania. Tu DLQ ma tę samą retencję co kolejka (1 dzień) → wiadomość może zniknąć z DLQ,
   zanim ktoś ją zauważy. Typowo DLQ ustawia się na 14 dni.
4. **Brak alarmu na DLQ.** `infrastructure/modules/monitoring/main.tf` tworzy tylko log groupy.
   DLQ bez alarmu na `ApproximateNumberOfMessagesVisible > 0` to „czarna dziura”.
5. **Brak kolejności.** Topic i kolejka są *standard*, nie FIFO. `CHARGING_STARTED` może dotrzeć
   przed `RESERVATION_CREATED`. Dla e-maili to akceptowalne.
6. **Head-of-line blocking w outboxie.** Poller nie ma licznika prób; jeśli jedno zdarzenie
   stale nie daje się opublikować (np. > 256 KB — limit SNS), cały batch jest wycofywany co 5 s,
   wcześniejsze zdarzenia są publikowane w kółko, a późniejsze wcale.
7. **Publish w transakcji DB.** Wywołanie sieciowe do SNS trzyma blokady wierszy i połączenie
   z puli; przy 100 zdarzeniach i wolnym SNS transakcja się wydłuża.
8. **Tabela outbox rośnie bez końca** — brak sprzątania wierszy `PUBLISHED`.
9. **Zapomniane `raw_message_delivery`** — klasyczny błąd: konsument dostaje kopertę SNS
   i parsowanie pada → wszystko w DLQ.

## 7. Ćwiczenia

Przygotowanie (raz):

```bash
brew install awscli
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=eu-central-1
alias awsl='aws --endpoint-url=http://localhost:4566'
./scripts/start-local.sh          # docker compose + terraform apply na LocalStack
TOPIC=arn:aws:sns:eu-central-1:000000000000:chargeflow-local-domain-events
Q=$(awsl sqs get-queue-url --queue-name chargeflow-local-notifications --query QueueUrl --output text)
DLQ=$(awsl sqs get-queue-url --queue-name chargeflow-local-notifications-dlq --query QueueUrl --output text)
```

**Ćw. 1 — obejrzyj topologię.**

```bash
awsl sns list-subscriptions-by-topic --topic-arn $TOPIC
awsl sns get-subscription-attributes --subscription-arn <arn z poprzedniej komendy>  # RawMessageDelivery=true
awsl sqs get-queue-attributes --queue-url $Q --attribute-names All   # RedrivePolicy, VisibilityTimeout, Policy
```

Znajdź w wyniku `maxReceiveCount` i `aws:SourceArn`. Porównaj z `modules/messaging/main.tf`.

**Ćw. 2 — raw delivery na własne oczy** (notification-service **wyłączony**, żeby nie zjadł wiadomości):

```bash
awsl sns publish --topic-arn $TOPIC --message '{"eventId":"11111111-1111-1111-1111-111111111111","eventType":"RESERVATION_CREATED","eventVersion":"1.0","occurredAt":"2026-09-14T17:00:00Z","aggregateId":"22222222-2222-2222-2222-222222222222","payload":{"customerId":"cust-1","customerEmail":"jan@example.com","chargingPointId":"33333333-3333-3333-3333-333333333333","validUntil":"2026-09-14T17:15:00Z"}}'
awsl sqs receive-message --queue-url $Q --attribute-names ApproximateReceiveCount
```

Body to dokładnie Twój JSON. Teraz wyłącz raw delivery
(`awsl sns set-subscription-attributes --subscription-arn <arn> --attribute-name RawMessageDelivery --attribute-value false`),
opublikuj jeszcze raz i porównaj. Przywróć `true` (albo `terraform apply`).

**Ćw. 3 — poison message → DLQ.** Uruchom notification-service (`./scripts/start-local.sh --services`) i:

```bash
awsl sns publish --topic-arn $TOPIC --message '{"eventId":"not-a-uuid","eventType":"UNKNOWN"}'
# po ~90-120 s (3 odbiory × 30 s visibility):
awsl sqs get-queue-attributes --queue-url $DLQ --attribute-names ApproximateNumberOfMessages
awsl sqs receive-message --queue-url $DLQ
```

W logach serwisu zobaczysz 3× `[CONSUMER] Failed to process messageId=...`. Bonus: przenieś
wiadomości z DLQ z powrotem (`awsl sqs start-message-move-task --source-arn <arn DLQ>`) —
jeśli Twoja wersja LocalStack to wspiera — i obserwuj, że wracają do DLQ.

**Ćw. 4 — idempotencja.** Przy działającym serwisie opublikuj komunikat z ćw. 2 **dwa razy**.
W logach (poziom DEBUG) pojawi się `Duplicate RESERVATION_CREATED eventId=...`, a w DynamoDB jest
jeden rekord: `awsl dynamodb get-item --table-name chargeflow-local-notifications --key '{"eventId":{"S":"11111111-1111-1111-1111-111111111111"}}'`.

**Ćw. 5 — outbox end-to-end.** Utwórz rezerwację przez API (token z `POST /dev/token`) i podejrzyj tabelę:

```bash
docker exec -it chargeflow-postgres psql -U chargeflow -d chargeflow \
  -c "select id, event_type, status, created_at, published_at from outbox_event order by created_at desc limit 5;"
```

Zatrzymaj LocalStack (`docker stop chargeflow-localstack`), utwórz kolejną rezerwację — API działa,
wiersz wisi jako `NEW`, a poller loguje błędy. Uruchom LocalStack ponownie (i `terraform apply`,
bo LocalStack bez persystencji traci zasoby) — wiersz zostanie opublikowany. To jest sens outboxa.

## 8. Pytania kontrolne

1. Dlaczego `charging-session-service` nie publikuje do SNS bezpośrednio w `createReservation()`?
   <details><summary>Odpowiedź</summary>Bo to byłby „dual write” bez wspólnej transakcji: commit DB i publish SNS mogą się rozjechać (zdarzenie zgubione albo zdarzenie o nieistniejącej rezerwacji). Outbox zapisuje zdarzenie w tej samej transakcji co rezerwację, a poller publikuje je asynchronicznie z gwarancją at-least-once.</details>

2. Co by się stało, gdyby w subskrypcji usunąć `raw_message_delivery = true`?
   <details><summary>Odpowiedź</summary>Body w SQS byłoby kopertą SNS (`Type`, `MessageId`, `Message` jako string...). `objectMapper.readValue(body, IncomingEvent.class)` nie znalazłby oczekiwanych pól/typów, każda wiadomość rzucałaby wyjątek, nie byłaby usuwana i po 3 odbiorach trafiałaby do DLQ.</details>

3. Skąd w tym systemie biorą się duplikaty i co je neutralizuje?
   <details><summary>Odpowiedź</summary>Z outboxa (publish przed commitem, rollback batcha), z SNS/SQS standard (at-least-once) i z wygaśnięcia visibility timeout w trakcie przetwarzania. Neutralizuje je stały `eventId` (= id wiersza outbox) i warunkowy `PutItem` z `attribute_not_exists(eventId)` w DynamoDB.</details>

4. Po jakim czasie (mniej więcej) poison message trafi do DLQ i od czego to zależy?
   <details><summary>Odpowiedź</summary>Po ok. 3 × 30 s ≈ 90 s (+ czas long pollingu). Zależy od `maxReceiveCount` (3) i visibility timeout (30 s — z kolejki, a także nadpisywany w `ReceiveMessage` przez `chargeflow.sqs.visibility-timeout-seconds`).</details>

5. Dlaczego nieudana wysyłka e-maila w obecnym kodzie nie trafia do DLQ?
   <details><summary>Odpowiedź</summary>Bo przy pierwszej próbie handler zapisuje rekord w DynamoDB (`saveIfNew`), a po błędzie zmienia status na FAILED i rzuca wyjątek. Przy ponownym dostarczeniu `saveIfNew` zwraca false, handler traktuje to jak duplikat, kończy się sukcesem i wiadomość jest usuwana. Żeby retry działał, idempotencja musiałaby rozróżniać PENDING/FAILED od SENT.</details>

6. Do czego służy warunek `aws:SourceArn` w queue policy i czemu lokalnie nie zauważysz jego błędu?
   <details><summary>Odpowiedź</summary>Ogranicza prawo `sqs:SendMessage` dla usługi SNS tylko do naszego topicu (ochrona przed confused deputy). LocalStack domyślnie nie egzekwuje polityk IAM/resource-based, więc błędna policy działa lokalnie, a w AWS wiadomości po cichu nie dochodzą.</details>
