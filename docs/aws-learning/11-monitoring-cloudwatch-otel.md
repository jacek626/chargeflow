# 11 — Monitoring: CloudWatch, Micrometer, OpenTelemetry

## 1. Po co to jest

System rozproszony to jak przesyłka kurierska przechodząca przez kilka sortowni:

- **Logi** — dziennik każdej sortowni: „o 10:02 przyjęto paczkę X”.
- **Metryki** — tablica w centrali: „ile paczek na godzinę, ile zagubionych”. Liczby, nie historie.
- **Trace'y** — numer przesyłki doklejany na każdym etapie, dzięki któremu widzisz całą trasę
  jednej paczki przez wszystkie sortownie.
- **Alarmy** — kontrolka, która zapala się, gdy „zagubionych > 0” przez 5 minut, zanim zadzwoni klient.

W ChargeFlow „przesyłką” jest np. rezerwacja: HTTP → Charging Session Service → outbox → SNS → SQS →
Notification Service → SES. Bez korelacji odpowiedź na pytanie „dlaczego klient nie dostał maila?”
oznacza grzebanie w dwóch strumieniach logów naraz.

## 2. Kluczowe pojęcia AWS

**CloudWatch Logs**
- **Log group** — „folder” logów jednej aplikacji/środowiska, np. `/chargeflow/dev/notification-service/ecs`.
  Na grupie ustawia się **retencję** (domyślnie: *Never expire*!) i uprawnienia.
- **Log stream** — sekwencja zdarzeń z jednego źródła (w ECS: jeden kontener jednego taska).
- **Sterownik `awslogs`** — sterownik logowania Dockera w ECS; wszystko, co kontener pisze na
  stdout/stderr, trafia do log streamu. Aplikacja nie musi znać CloudWatch.
- **Logs Insights** — język zapytań po logach (`fields`, `filter`, `stats`), płatny za przeskanowane GB.
- **Metric filter** — wyciąga metrykę z logów (np. liczba linii z `ERROR`).

**CloudWatch Metrics**
- **Metric** = namespace + nazwa + **dimensions** (np. `AWS/SQS`, `ApproximateNumberOfMessagesVisible`,
  `QueueName=...`). Usługi AWS publikują swoje metryki same i za darmo (podstawowe).
- **Custom metrics** — metryki aplikacji (np. przez `PutMetricData` albo Micrometer CloudWatch registry);
  płatne per metryka.
- **Container Insights** — dodatkowe metryki CPU/pamięć/sieć na poziomie taska/serwisu ECS (płatne).

**CloudWatch Alarms i Dashboards**
- **Alarm** obserwuje jedną metrykę (lub wyrażenie): próg, `period`, `evaluation_periods`;
  stany `OK` / `ALARM` / `INSUFFICIENT_DATA`; akcja zwykle = publikacja na temat SNS (e-mail, Slack).
- **Dashboard** — zestaw wykresów; płatny powyżej darmowego limitu.

**Tracing**
- **Trace** — cała ścieżka żądania; **span** — jeden jej odcinek (wywołanie HTTP, zapytanie SQL, publish SNS).
- **traceId / spanId** — identyfikatory; **W3C Trace Context** przenosi je nagłówkiem `traceparent`.
- **Propagacja** — przekazanie kontekstu dalej: przez HTTP (nagłówki) albo przez kolejkę
  (atrybuty wiadomości SNS/SQS). Bez tego każdy serwis zaczyna nowy trace.
- **Sampling** — jaki odsetek trace'ów zapisywać (1.0 = wszystkie).
- **Exporter / Collector** — coś musi wysłać spany do backendu: OTLP exporter w aplikacji, kolektor
  (np. AWS Distro for OpenTelemetry, ADOT, jako sidecar w ECS) i backend (AWS X-Ray, Jaeger, Tempo...).
- **AWS X-Ray** — natywny backend trace'ów w AWS (mapa serwisów, oś czasu spanów).

**Micrometer (perspektywa Springa)**
- `MeterRegistry` — fasada metryk (jak SLF4J dla logów); rejestr decyduje, *gdzie* metryki trafiają
  (Prometheus, CloudWatch, OTLP...).
- **Micrometer Tracing** + `micrometer-tracing-bridge-otel` — fasada trace'ów oparta o OpenTelemetry SDK;
  wstawia `traceId`/`spanId` do MDC logów.

## 3. Jak to działa w ChargeFlow

### 3.1. Moduł `infrastructure/modules/monitoring` — co naprawdę jest zdefiniowane

Cały moduł to **dwie log groups**, nic więcej:

```hcl
# infrastructure/modules/monitoring/main.tf:4-6
resource "aws_cloudwatch_log_group" "charging_session_service" {
  name              = "/chargeflow/${var.environment}/charging-session-service"
  retention_in_days = var.log_retention_days
```

(analogicznie `notification-service` w `main.tf:16-24`). Retencja: domyślnie 30 dni
(`infrastructure/modules/monitoring/variables.tf:15-19`), w dev z `log_retention_days`
(`infrastructure/environments/dev/main.tf:142-148`, domyślnie 30), w local 7
(`infrastructure/environments/local/main.tf:31-37`).

**Nie ma**: `aws_cloudwatch_metric_alarm`, `aws_cloudwatch_dashboard`, metric filters, tematu SNS
na alarmy, X-Ray ani kolektora OTel. `docs/architecture/observability.md` ma status `PLACEHOLDER`.

Uwaga: **te log groups są nieużywane.** ECS pisze do innych grup, zdefiniowanych w module `ecs`:

```hcl
# infrastructure/modules/ecs/main.tf:43-47
resource "aws_cloudwatch_log_group" "charging_session" {
  name              = "/chargeflow/${var.environment}/charging-session-service/ecs"
  retention_in_days = 30
```

(oraz `/chargeflow/${env}/notification-service/ecs` w `main.tf:53-59`). Skutki:
1. W konsoli zobaczysz 4 grupy, z czego 2 (z modułu `monitoring`) zawsze puste.
2. Zmienna `log_retention_days` **nie wpływa** na faktyczne logi — tam retencja jest na sztywno 30.

### 3.2. ECS → CloudWatch Logs (`awslogs`)

```hcl
# infrastructure/modules/ecs/main.tf:230-237
logConfiguration = {
  logDriver = "awslogs"
  options = {
    "awslogs-group"         = aws_cloudwatch_log_group.charging_session.name
    "awslogs-region"        = var.aws_region
    "awslogs-stream-prefix" = "ecs"
  }
}
```

Nazwa streamu ma postać `ecs/<nazwa-kontenera>/<task-id>`, np.
`ecs/charging-session-service/3f9c...`. Uprawnienia do zapisu daje **execution role**
(`AmazonECSTaskExecutionRolePolicy`, `modules/ecs/main.tf:81-84`) — zob. plik 10.

Klaster ma włączone **Container Insights** (`infrastructure/modules/ecs/main.tf:18-21`) — to jedyne
„metryki aplikacyjne” w AWS, jakie realnie dostaniesz (CPU, pamięć, liczba tasków).

Health check kontenera (`modules/ecs/main.tf:242-248`) wywołuje `curl -f http://localhost:8080/actuator/health`.
Uwaga: obraz runtime to `eclipse-temurin:25-jre` (`charging-session-service/Dockerfile:11`) — warto
sprawdzić, czy zawiera `curl` (`docker run --rm eclipse-temurin:25-jre which curl`); jeśli nie, ECS
będzie oznaczał taski jako niezdrowe.

### 3.3. Spring: zależności i konfiguracja

Oba serwisy mają ten sam zestaw (Spring Boot 4.1.1, `charging-session-service/pom.xml:10`):

| Zależność | Po co | CSS | NS |
|---|---|---|---|
| `spring-boot-starter-actuator` | `/actuator/*`, Micrometer | `pom.xml:83` | `pom.xml:104` |
| `micrometer-tracing-bridge-otel` | traceId/spanId przez OpenTelemetry SDK | `pom.xml:89` | `pom.xml:68` |
| `micrometer-registry-prometheus` (runtime) | `/actuator/prometheus` | `pom.xml:95` | `pom.xml:74` |

`application.yml` (CSS: `charging-session-service/src/main/resources/application.yml:48-63`,
NS: `notification-service/src/main/resources/application.yml:31-46`):

```yaml
logging:
  structured:
    format:
      console: ecs          # JSON w formacie Elastic Common Schema na stdout
management:
  endpoints.web.exposure.include: health,info,metrics,prometheus
  endpoint.health.show-details: always
  tracing.sampling.probability: 1.0
```

- **Structured logging (ECS)** — każda linia to JSON (`@timestamp`, `log.level`, `message`,
  `service.name`, ... + wpisy z MDC). Idealne dla Logs Insights, które samo parsuje JSON.
  „ECS” to tu *Elastic Common Schema*, nie *Elastic Container Service* — przypadkowa zbieżność nazw.
- **Sampling 1.0** — próbkuj 100% żądań.

Metryki biznesowe (Micrometer counters):

| Licznik | Gdzie |
|---|---|
| `chargeflow.reservations.created` / `.cancelled` | `charging-session-service/src/main/java/pl/chargeflow/session/reservation/ReservationService.java:54`, `:94` |
| `chargeflow.reservations.expired` | `.../reservation/ReservationExpirationJob.java:55` |
| `chargeflow.sessions.started` / `.completed` | `.../session/ChargingSessionService.java:79`, `:96` |
| `chargeflow.notifications.sent` / `.failed` (tag `eventType`) | `notification-service/src/main/java/pl/chargeflow/notification/consumer/DomainEventRouter.java:36-38` |

W formacie Prometheus nazwy zmienią się na np. `chargeflow_reservations_created_total`.

### 3.4. Gdzie te metryki i trace'y trafiają? (Uwagi)

To najważniejsza część tego rozdziału — kod **produkuje** sygnały, ale nie ma ich **odbiorcy**:

1. **`/actuator/prometheus` jest niedostępny.** Endpoint jest wystawiony przez Actuatora, ale
   `SecurityConfig` przepuszcza tylko health:
   `charging-session-service/src/main/java/pl/chargeflow/session/config/SecurityConfig.java:33`
   (`/actuator/health`), a wszystko inne spoza listy dostaje `.anyRequest().denyAll()` (`:39`).
   To samo w `notification-service/.../config/SecurityConfig.java:23-24`. W AWS nic go też nie
   scrape'uje (brak Prometheusa/AMP).
2. **Brak eksportu metryk do CloudWatch.** Nie ma `micrometer-registry-cloudwatch2` ani OTLP. Task role
   ma `cloudwatch:PutMetricData` (`infrastructure/modules/ecs/main.tf:175-183`), ale nikt go nie używa.
   Liczniki żyją tylko w pamięci procesu.
3. **Brak eksportu trace'ów.** Jest bridge OTel, ale nie ma exportera (`opentelemetry-exporter-otlp`),
   kolektora ADOT ani X-Ray. Trace'y w najlepszym razie dają `traceId` w logach.
4. **Czy `traceId` w ogóle trafia do logów?** W Spring Boot 4 autokonfiguracja tracingu została
   wydzielona do osobnych modułów (m.in. `spring-boot-micrometer-tracing*` / starter OpenTelemetry).
   W `pom.xml` jest tylko goły `micrometer-tracing-bridge-otel`. Nie da się tego potwierdzić bez
   uruchomienia (wymaga Javy 25) — **sprawdź w ćwiczeniu 1**, czy w JSON-ie logu są pola
   `traceId`/`spanId`. Jeśli nie, `management.tracing.*` nie ma efektu.
5. **`traceId` w odpowiedziach błędów to losowy UUID**, nie trace:
   ```java
   // charging-session-service/src/main/java/pl/chargeflow/session/common/GlobalExceptionHandler.java:123-126
   // Placeholder until CF-025 wires real distributed tracing.
   private String newTraceId() { return UUID.randomUUID().toString(); }
   ```
   CF-025 jest oznaczone jako `[x]` w `docs/exec-plans/backlog.md:53`, a placeholder został.
   Klient podający `traceId` z błędu nie pozwoli znaleźć niczego w logach.

### 3.5. Propagacja przez SNS/SQS i korelacja

Kontekst trace'u **nie przechodzi** przez kolejkę:

- Publikacja w `SnsDomainEventPublisher.java:36-39` (`charging-session-service/src/main/java/pl/chargeflow/session/outbox/`)
  ustawia tylko `topicArn` i `message` — bez `messageAttributes` (np. `traceparent`).
- Publikację wywołuje `@Scheduled` poller (`OutboxPoller.java:25-36`) — z definicji inny wątek
  i inny kontekst niż żądanie HTTP, które utworzyło rezerwację.
- Konsument `SqsEventConsumer.java:51-56` (`notification-service/.../consumer/`) nie prosi o atrybuty
  wiadomości i nie odtwarza kontekstu. Dodatkowo subskrypcja ma `raw_message_delivery = true`
  (`infrastructure/modules/messaging/main.tf:70`), więc ewentualne atrybuty SNS trafiłyby do SQS
  jako atrybuty wiadomości — trzeba by je jawnie odczytać.

**Naturalny identyfikator korelacji** to `eventId` z koperty zdarzenia
(`SnsDomainEventPublisher.java:52`), który Notification Service loguje
(`.../handler/ReservationCreatedHandler.java:62`: `RESERVATION_CREATED sent eventId={}`).
Uwaga: strona nadawcy loguje `aggregateId`, **nie** `eventId` (`SnsDomainEventPublisher.java:40`),
a strona odbiorcy odwrotnie — więc po samych logach nie połączysz wpisów; trzeba zajrzeć do tabeli
`outbox` (eventId ↔ aggregateId) albo do DynamoDB.

### 3.6. Health i ALB

ALB sprawdza `/actuator/health` (`infrastructure/modules/alb/main.tf:23-25`), endpoint jest publiczny
(`SecurityConfig.java:33`), a `show-details: always` zwraca szczegóły (stan DB, dysku). Przez ALB
każdy z internetu zobaczy, czy baza żyje — lepiej `when-authorized` albo osobny port zarządzania.

## 4. Diagram

```
                ┌─────────────────────── ECS Fargate task ─────────────────────────┐
 HTTP ─► ALB ─► │ charging-session-service                                         │
   (health:     │  Micrometer ──► MeterRegistry (Prometheus, in-memory) ──X brak    │
   /actuator/   │  Tracing (bridge-otel) ──► traceId w MDC? ──X brak exportera       │
    health)     │  Logback ECS JSON ──► stdout                                       │
                └───────────────────────────┬───────────────────────────────────────┘
                                            │ awslogs driver (execution role)
                                            ▼
                     CloudWatch Logs: /chargeflow/dev/charging-session-service/ecs
                                      stream: ecs/charging-session-service/<task-id>
                                      retention 30 d (hardcoded)

  outbox poller ──SNS publish (bez traceparent)──► SNS ──raw──► SQS ──► notification-service
                                                                          │ stdout JSON
                                                                          ▼
                     CloudWatch Logs: /chargeflow/dev/notification-service/ecs

  Nieużywane:  /chargeflow/dev/charging-session-service   (moduł monitoring)
               /chargeflow/dev/notification-service       (moduł monitoring)
  Brak:        Alarms, Dashboards, X-Ray / ADOT collector, CloudWatch metrics registry
  Jest:        Container Insights (CPU/RAM tasków), metryki usług AWS (SQS, ALB, RDS) — za darmo
```

Tak mógłby wyglądać docelowy pipeline (nie istnieje w kodzie):
`app (OTLP) → ADOT sidecar → X-Ray (trace'y) + CloudWatch (metryki EMF)`.

## 5. Lokalnie vs AWS (dev)

| | Lokalnie | AWS dev |
|---|---|---|
| Logi | JSON na stdout terminala (`./mvnw spring-boot:run`) | CloudWatch Logs przez `awslogs`, grupy `.../ecs` |
| Log groups z Terraformu | `environments/local` tworzy je w LocalStack (retencja 7) — nikt do nich nie pisze | 4 grupy, 2 używane |
| Metryki | `curl localhost:8080/actuator/metrics` → 401/403 przez `denyAll` | Container Insights + darmowe metryki usług AWS |
| Trace'y | ewentualnie `traceId` w logach (do weryfikacji) | to samo; brak X-Ray |
| Alarmy | brak | brak |

## 6. Koszty i pułapki

Ceny orientacyjne dla eu-central-1 — zawsze sprawdź aktualny cennik CloudWatch.

- **Ingestion logów** (~0,6 USD/GB) zwykle kosztuje więcej niż przechowywanie (~0,03 USD/GB-mies.).
  JSON jest „gadatliwszy” od zwykłego tekstu; logi DEBUG na produkcji szybko bolą.
- **Retencja „Never expire”** — domyślna dla grup tworzonych automatycznie. Tu wszystkie grupy mają 30 dni — dobrze.
- **Rozjazd konfiguracji** — zmienna `log_retention_days` sugeruje, że sterujesz retencją, a faktyczne
  grupy mają 30 na sztywno (3.1).
- **Container Insights** — płatne metryki per task/serwis; w małym dev to kilka USD/mies., ale warto wiedzieć,
  że jest włączone (`modules/ecs/main.tf:18-21`).
- **Custom metrics** — każda kombinacja nazwa+dimensions to osobna płatna metryka. Tag `eventType` na
  `chargeflow.notifications.*` to OK (mała kardynalność); tag z `customerId` byłby katastrofą.
- **Sampling 1.0** — darmowy dopóki nic nie eksportuje; po podpięciu X-Ray płacisz za każdy trace.
- **Logs Insights** — płatne za przeskanowane GB; zawężaj zakres czasu i grupę.
- **Brak alarmu na DLQ** — najważniejsza luka operacyjna: wiadomość w DLQ = klient bez maila, a nikt
  się o tym nie dowie.
- **Placeholder traceId** w `ApiError` daje złudzenie korelacji.

## 7. Ćwiczenia

Ćwiczenia 1–3: lokalnie, darmowe (wymagają Javy 25, Dockera, `brew install awscli jq`).
Ćwiczenia 4–5: na wdrożonym dev — **kosztują** (ingestion/skan logów, alarm ~0,10 USD/mies.).

**Ćwiczenie 1 — czy jest `traceId` w logach?**

```bash
./scripts/start-local.sh --services        # LocalStack + Postgres + oba serwisy
# w osobnym terminalu, gdy serwisy wstaną (~30 s):
TOKEN=$(curl -s -X POST 'http://localhost:8080/dev/token' -d 'role=CUSTOMER' | jq -r .access_token)
curl -s http://localhost:8080/api/stations | jq '.[0]'
```

Skrypt uruchamia serwisy z `-q` w tle — prościej odpalić CSS ręcznie w terminalu z eksportami
wypisanymi przez skrypt i patrzeć na stdout. Przepuść jedną linię logu przez `jq` i sprawdź klucze:
czy są `traceId` i `spanId`? Zanotuj wynik — to weryfikuje uwagę z 3.4 pkt 4.

**Ćwiczenie 2 — `traceId` z błędu.**

```bash
curl -s http://localhost:8080/api/stations/00000000-0000-0000-0000-000000000000 | jq
```

Dostaniesz `ApiError` z polem `traceId`. Poszukaj tej wartości w logach CSS (nie znajdziesz — patrz
`GlobalExceptionHandler.java:123-126`). Zaproponuj, skąd wziąć prawdziwy identyfikator
(podpowiedź: wstrzyknięty `io.micrometer.tracing.Tracer` i `tracer.currentSpan()`).

**Ćwiczenie 3 — korelacja przez kolejkę.** Utwórz rezerwację (ID punktu weź z `/api/stations/{id}`):

```bash
curl -s -X POST http://localhost:8080/api/reservations \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"chargingPointId":"<UUID-punktu>"}' | jq
```

W logach CSS znajdź `[SNS] Published event=RESERVATION_CREATED aggregateId=...`, a w logach NS
`[NOTIFICATION] RESERVATION_CREATED sent eventId=...`. Czy `traceId` (jeśli jest) się zgadza?
Jak połączysz te dwa wpisy? (Podpowiedź: `docker exec chargeflow-postgres psql -U chargeflow -d chargeflow
-c "select id, aggregate_id, event_type, status from outbox_event order by created_at desc limit 5"` —
schemat: `charging-session-service/src/main/resources/db/migration/V5__create_outbox_event.sql`;
`id` z outboxu to `eventId` w kopercie, `SnsDomainEventPublisher.java:52`.)

**Ćwiczenie 4 — logi z dev (💰 ingestion już płacisz; odczyt `tail` jest tani).**

```bash
aws logs describe-log-groups --log-group-name-prefix /chargeflow/dev \
  --query 'logGroups[].[logGroupName,retentionInDays,storedBytes]' --output table
aws logs tail /chargeflow/dev/charging-session-service/ecs --since 15m --follow
aws logs tail /chargeflow/dev/notification-service/ecs --since 1h --filter-pattern '"RESERVATION_CREATED"'
```

Zwróć uwagę, że grupy bez sufiksu `/ecs` mają `storedBytes = 0`.

**Ćwiczenie 5 — Logs Insights + alarm na DLQ (💰).**

```bash
QID=$(aws logs start-query --log-group-name /chargeflow/dev/notification-service/ecs \
  --start-time $(date -v-1H +%s) --end-time $(date +%s) \
  --query-string 'fields @timestamp, `log.level`, message | filter `log.level` = "ERROR" | sort @timestamp desc | limit 20' \
  --query queryId --output text)
sleep 5; aws logs get-query-results --query-id "$QID"

aws cloudwatch put-metric-alarm --alarm-name chargeflow-dev-dlq-not-empty \
  --namespace AWS/SQS --metric-name ApproximateNumberOfMessagesVisible \
  --dimensions Name=QueueName,Value=chargeflow-dev-notifications-dlq \
  --statistic Maximum --period 300 --evaluation-periods 1 \
  --threshold 0 --comparison-operator GreaterThanThreshold
# sprzątanie:
aws cloudwatch delete-alarms --alarm-names chargeflow-dev-dlq-not-empty
```

Alarm stworzony ręcznie łamie zasadę „nic ręcznie” z `infrastructure/CLAUDE.md` — traktuj to jako
eksperyment, a docelowo napisz go jako `aws_cloudwatch_metric_alarm` w module `monitoring`
(w osobnym zadaniu, nie w ramach tej lektury).

## 8. Pytania kontrolne

1. Czym różni się log group od log streamu i ile streamów powstanie dla serwisu z `desired_count = 2`?
   <details><summary>Odpowiedź</summary>Group to kontener logów z retencją i uprawnieniami; stream to sekwencja z jednego źródła. Przy `awslogs` jeden stream na kontener w tasku (`ecs/&lt;kontener&gt;/&lt;task-id&gt;`), więc 2 — plus nowe przy każdym restarcie/deployu.</details>

2. Dlaczego zmiana `log_retention_days` w `environments/dev` nie zmieni retencji logów aplikacji?
   <details><summary>Odpowiedź</summary>Zmienna trafia do modułu `monitoring`, którego grupy są nieużywane. ECS pisze do grup z modułu `ecs` (`.../ecs`) z retencją 30 na sztywno (`modules/ecs/main.tf:43-59`).</details>

3. Co trzeba by dodać, aby liczniki `chargeflow.*` były widoczne w CloudWatch?
   <details><summary>Odpowiedź</summary>Rejestr wysyłający je do AWS: np. `micrometer-registry-cloudwatch2` (używa `cloudwatch:PutMetricData` z task role — uprawnienie już jest) albo eksport OTLP do kolektora ADOT. Ewentualnie Prometheus scrape — ale wtedy trzeba też odblokować endpoint w `SecurityConfig`.</details>

4. Dlaczego trace z żądania `POST /api/reservations` nie kontynuuje się w Notification Service?
   <details><summary>Odpowiedź</summary>Publikację robi `@Scheduled` poller (inny kontekst), `PublishRequest` nie ma atrybutu `traceparent`, a konsument SQS nie odczytuje atrybutów i nie tworzy spanu-dziecka. Do tego brak exportera — nie ma gdzie zobaczyć trace'u.</details>

5. Jaki jeden alarm dodałbyś jako pierwszy i na jakiej metryce?
   <details><summary>Odpowiedź</summary>`AWS/SQS ApproximateNumberOfMessagesVisible` na `chargeflow-dev-notifications-dlq` &gt; 0 — oznacza, że powiadomienie nie zostało dostarczone po `maxReceiveCount` próbach. Ewentualnie `ApproximateAgeOfOldestMessage` na kolejce głównej (konsument nie nadąża / nie działa).</details>

6. Czy `traceId` w odpowiedzi błędu API pozwala znaleźć żądanie w logach? Dlaczego?
   <details><summary>Odpowiedź</summary>Nie — `GlobalExceptionHandler.newTraceId()` generuje losowy UUID (placeholder „until CF-025”), niezwiązany z kontekstem tracingu i nigdzie nielogowany.</details>
