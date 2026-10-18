# 07 — SES: wysyłka e-maili

## 1. Po co to jest

Kierowca ma dostać e-mail: „rezerwacja potwierdzona”, „ładowanie zakończone, 27,45 kWh, 68,34 PLN”.
Własny serwer SMTP to kłopot: reputacja IP, SPF/DKIM, listy spamowe, bounce'y. **Amazon SES**
(Simple Email Service) to „poczta firmowa jako usługa” — wołasz API `SendEmail`, a AWS dba
o dostarczenie.

Analogia: SES to **urząd pocztowy, który najpierw sprawdza Twój dowód** (weryfikacja nadawcy),
a nowym klientom pozwala wysyłać listy tylko do znajomych, których adresy też zweryfikował
(**sandbox**), dopóki nie udowodnisz, że nie jesteś spamerem.

## 2. Kluczowe pojęcia AWS

| Pojęcie | Co to jest |
|---|---|
| **Identity** | Zweryfikowany nadawca: pojedynczy **adres e-mail** (klik w link weryfikacyjny) albo **domena** (rekordy DNS, zwykle z DKIM). Bez zweryfikowanej identity SES odrzuci wysyłkę (`MessageRejected`). |
| **SES v1 vs v2 API** | Dwa API tej samej usługi. Nowszy `sesv2` (`CreateEmailIdentity`, `SendEmail` z `Simple`/`Raw`/`Template` content). Akcje IAM mają wspólny prefiks `ses:`. |
| **Sandbox** | Stan startowy każdego konta w każdym regionie: wysyłka **tylko do zweryfikowanych odbiorców**, limit 200 e-maili / 24 h i 1 e-mail/s. Wyjście = wniosek o *production access* (ręcznie, przez konsolę/support — nie przez Terraform). |
| **Sending quota / rate** | Limity dzienne i na sekundę; przekroczenie → `Throttling`/`LimitExceeded`. |
| **Bounce / Complaint** | Asynchroniczne zdarzenia po wysyłce (adres nie istnieje, odbiorca kliknął „spam”). Dostępne przez configuration set → SNS/EventBridge. Za wysoki odsetek = ograniczenie konta. |
| **Configuration set** | Ustawienia przypinane do wysyłki: event destinations, tracking, reputacja. |
| **DKIM / SPF / DMARC** | Mechanizmy uwierzytelnienia domeny nadawcy; bez nich maile lądują w spamie. |

## 3. Jak to działa w ChargeFlow

### 3.1. Terraform — `infrastructure/modules/ses`

Cały moduł to jeden zasób (`infrastructure/modules/ses/main.tf:4-11`):

```hcl
resource "aws_sesv2_email_identity" "sender" {
  email_identity = var.from_email
  tags = { Environment = var.environment, Service = "notification-service" }
}
```

- Identity typu **adres e-mail**, nie domena — brak DKIM, brak rekordów DNS, brak configuration
  setu, brak obsługi bounce/complaint.
- Adres: lokalnie na sztywno `noreply@chargeflow.pl` (`infrastructure/environments/local/main.tf:23-28`),
  w dev zmienna `ses_from_email` z defaultem `noreply@chargeflow.pl`
  (`infrastructure/environments/dev/variables.tf:54-58`).
- W prawdziwym AWS `terraform apply` **tylko inicjuje** weryfikację: SES wysyła link na ten adres.
  Dopóki ktoś nie kliknie, identity jest w stanie *pending* i wysyłka nie działa. Terraform nie ma
  jak tego „dokończyć” — skrzynka `noreply@...` musi realnie istnieć i ktoś musi ją odczytać.
- Output `email_identity_arn` (`infrastructure/modules/ses/outputs.tf:8-11`) nie jest nigdzie użyty
  (np. do zawężenia IAM).

**IAM** (`infrastructure/modules/ecs/main.tf:167-170`):

```hcl
Sid = "SESSend", Action = ["ses:SendEmail", "ses:SendRawEmail"], Resource = ["*"]
```

Kod używa SDK `sesv2` `SendEmail`; w IAM ta operacja jest autoryzowana akcją `ses:SendEmail`.
`Resource = "*"` pozwala wysyłać jako dowolna identity na koncie — dałoby się zawęzić do ARN
identity (i ewentualnie warunkiem `ses:FromAddress`). Uwaga: rola jest współdzielona przez oba
serwisy (`infrastructure/modules/ecs/main.tf:199, 212`), więc charging-session-service też może
wysyłać maile.

Zmienne dla kontenera (`infrastructure/modules/ecs/main.tf:282-283`): `SES_ENABLED=true`,
`SES_FROM_ADDRESS=var.ses_from_address`.

### 3.2. Java — `notification-service`

Przełącznik `chargeflow.ses.enabled` (`notification-service/src/main/resources/application.yml:17-19`,
domyślnie `false`) wybiera jedną z dwóch implementacji portu
`EmailServicePort` (`notification-service/src/main/java/pl/chargeflow/notification/email/EmailServicePort.java:6-13`):

- `NoOpEmailService` — tylko `log.debug("[EMAIL][NOOP] ...")`
  (`notification-service/src/main/java/pl/chargeflow/notification/email/NoOpEmailService.java:11-21`);
- `EmailService` — prawdziwe SES v2.

Klient `SesV2Client` z opcjonalnym `endpointOverride`
(`notification-service/src/main/java/pl/chargeflow/notification/config/SesConfig.java:15-30`) —
ten sam wzorzec co dla SNS/SQS/DynamoDB.

Wysyłka (`notification-service/src/main/java/pl/chargeflow/notification/email/EmailService.java:72-95`):

```java
if (toEmail == null || toEmail.isBlank()) {
    log.warn("[EMAIL] Cannot send '{}' — recipient email is empty", subject);
    return;                                   // uwaga: bez wyjątku!
}
ses.sendEmail(SendEmailRequest.builder()
        .fromEmailAddress(fromAddress)
        .destination(Destination.builder().toAddresses(toEmail).build())
        .content(EmailContent.builder().simple(Message.builder()
                .subject(Content.builder().data(subject).charset("UTF-8").build())
                .body(Body.builder().text(Content.builder().data(body).charset("UTF-8").build()).build())
                .build()).build())
        .build());
```

- Treść to zwykły tekst (`Body.text`), bez HTML i bez szablonów SES — treści są zaszyte w metodach
  `sendReservationCreated`, `sendChargingCompleted` itd. (linie 34-70).
- Wyjątek SES jest logowany i rzucany dalej → handler ustawia status `FAILED` w DynamoDB,
  publikuje `NOTIFICATION_FAILED` z powodem `EMAIL_SEND_FAILED` i rzuca wyjątek, żeby SQS ponowił
  (zob. [05](05-messaging-sns-sqs.md), sekcja 3.3 — ponowienie w praktyce nie wyśle maila).
- **Sukces `SendEmail` ≠ doręczenie.** SES przyjął wiadomość; bounce może przyjść minutę później.
  Kontrakt przewiduje powód `SES_BOUNCE` (`docs/contracts/events/README.md`), ale w kodzie nie ma
  żadnej obsługi bounce'ów — jedyny powód to `EMAIL_SEND_FAILED`.

**Skąd adres odbiorcy?** Z claimu `email` w JWT, odczytanego przy tworzeniu rezerwacji
(`charging-session-service/src/main/java/pl/chargeflow/session/reservation/ReservationController.java:35-36`),
zapisanego w rezerwacji (migracja `V6__add_customer_email_to_reservation.sql`) i przenoszonego
w payloadzie zdarzenia jako `customerEmail`.

> **Uwaga:** gdy claimu brak, zapisywany jest pusty string. Wtedy `EmailService` tylko loguje
> ostrzeżenie i wraca normalnie — handler oznacza powiadomienie jako **`SENT`** i publikuje
> `NOTIFICATION_SENT`, choć żaden e-mail nie wyszedł. To realne ryzyko w dev: frontend wysyła
> **access token** Cognito (`frontend/src/app/auth/auth.interceptor.ts:12`), a access tokeny Cognito
> standardowo nie zawierają claimu `email` (ma go ID token; w module Cognito nie ma triggera
> pre-token-generation, który by go dodał). Lokalnie problem nie występuje, bo token z
> `POST /dev/token` ma claim `email` (`charging-session-service/src/main/java/pl/chargeflow/session/config/LocalDevController.java:37-42`).
> Warto zweryfikować na dev, dekodując realny token.

Uwaga: w e-mailach jako „charging point code” przekazywany jest `chargingPointId.toString()`
(np. `ReservationCreatedHandler.java:58`) — klient zobaczy UUID, a nie czytelny kod punktu;
spec wymienia też stację (`docs/product-specs/chargeflow-spec.md`, sekcja 15).

## 4. Diagram

```
 RESERVATION_CREATED (SQS)
        │  payload.customerEmail  ◄── claim "email" z JWT (przy tworzeniu rezerwacji)
        ▼
 ReservationCreatedHandler
        │ saveIfNew → DynamoDB (PENDING)
        ▼
 EmailServicePort ──── chargeflow.ses.enabled=false ──► NoOpEmailService (log)
        │
        │ =true
        ▼
 EmailService.send()
        │ toEmail pusty? ──► log.warn + return  ──► status SENT (!)
        ▼
 SesV2Client.sendEmail(from = SES_FROM_ADDRESS)
        │                         ┌───────────────────────────────────────┐
        ├──── LocalStack ────────►│ zapisane w /_aws/ses, nic nie wychodzi│
        │                         └───────────────────────────────────────┘
        │                         ┌───────────────────────────────────────┐
        └──── AWS (sandbox) ─────►│ identity noreply@... zweryfikowana?   │
                                  │ odbiorca zweryfikowany? (sandbox)     │
                                  │ tak → skrzynka kierowcy               │
                                  │ nie → MessageRejected → FAILED        │
                                  └───────────────────────────────────────┘
```

## 5. Lokalnie vs AWS (dev)

| Aspekt | LocalStack | AWS dev |
|---|---|---|
| Domyślnie | `scripts/start-local.sh` ustawia **`SES_ENABLED=false`** → NoOp, SES w ogóle nie jest wołany | `SES_ENABLED=true` w task definition |
| Weryfikacja identity | `aws_sesv2_email_identity` jest od razu „zweryfikowana”, żadnego maila | mail z linkiem na adres nadawcy; bez kliknięcia wysyłka nie działa |
| Sandbox | nie istnieje — dowolny odbiorca | tylko zweryfikowani odbiorcy, 200/dzień, 1/s |
| Gdzie są maile | nic nie wychodzi; podgląd przez `GET http://localhost:4566/_aws/ses` | prawdziwe skrzynki |
| Bounce/complaint | brak | realne, wpływają na reputację konta |
| IAM | nieegzekwowane | rola zadania ECS |

## 6. Koszty i pułapki

**Koszty:** ok. $0.10 za 1000 e-maili (+ transfer załączników); przy wysyłce z EC2/ECS bywa
darmowa pula — sprawdź aktualny cennik SES. Dla V1 koszt pomijalny; prawdziwy „koszt” to
reputacja konta.

**Pułapki:**

1. **Sandbox** — najczęstsze zaskoczenie: wszystko działa do Twojego zweryfikowanego maila,
   a do klientów leci `MessageRejected: Email address is not verified`. Wyjście z sandboxa to
   ręczny wniosek per region, poza Terraformem.
2. **Niezweryfikowana identity nadawcy** — `terraform apply` przeszedł, ale nikt nie kliknął linku.
3. **Region** — identity i sandbox są **per region**. Kod używa `AWS_REGION` (eu-central-1);
   identity zweryfikowana w innym regionie nic nie da.
4. **Adres e-mail zamiast domeny** — bez DKIM/SPF maile z `noreply@chargeflow.pl` mają duże szanse
   trafić do spamu, a do tego potrzebna jest realna skrzynka do weryfikacji.
5. **Brak obsługi bounce/complaint** — przy wysokim bounce rate (np. > 5%) AWS może zablokować
   wysyłkę na całym koncie. Brak configuration setu = brak wiedzy, co się dzieje po wysyłce.
6. **Ciche „sukcesy”** — pusty adres odbiorcy kończy się statusem `SENT` (sekcja 3.2).
7. **Throttling** — 1 msg/s w sandboxie; burst zdarzeń (np. job wygaszający wiele rezerwacji
   naraz) da błędy `Throttling`, które tu zamieniają się w `FAILED` bez skutecznego retry.
8. **PII w logach** — `EmailService` loguje adres odbiorcy na INFO/ERROR
   (`EmailService.java:90, 92`); w CloudWatch to dane osobowe.

## 7. Ćwiczenia

```bash
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=eu-central-1
alias awsl='aws --endpoint-url=http://localhost:4566'
```

**Ćw. 1 — identity z Terraformu.**

```bash
awsl sesv2 list-email-identities
awsl sesv2 get-email-identity --email-identity noreply@chargeflow.pl
```

Porównaj z `infrastructure/modules/ses/main.tf`. Zwróć uwagę na `VerifiedForSendingStatus`.

**Ćw. 2 — wyślij maila ręcznie i podejrzyj go w LocalStack.**

```bash
awsl sesv2 send-email --from-email-address noreply@chargeflow.pl \
  --destination ToAddresses=jan@example.com \
  --content '{"Simple":{"Subject":{"Data":"Test"},"Body":{"Text":{"Data":"Hello from LocalStack"}}}}'
curl -s http://localhost:4566/_aws/ses | python3 -m json.tool
```

Spróbuj nadawcy, którego nie ma w identities (np. `--from-email-address hacker@evil.com`) i sprawdź,
czy Twoja wersja LocalStack odrzuca wysyłkę tak jak AWS.

**Ćw. 3 — notification-service z prawdziwym (lokalnym) SES.** Uruchom serwis z `SES_ENABLED=true`
zamiast `false` (pozostałe zmienne jak w `scripts/start-local.sh`):

```bash
cd notification-service
AWS_REGION=eu-central-1 AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
AWS_ENDPOINT_URL=http://localhost:4566 SQS_ENABLED=true \
SQS_QUEUE_URL=$(aws --endpoint-url=http://localhost:4566 sqs get-queue-url --queue-name chargeflow-local-notifications --query QueueUrl --output text) \
DYNAMODB_NOTIFICATIONS_TABLE=chargeflow-local-notifications SES_ENABLED=true \
./mvnw spring-boot:run
```

Utwórz rezerwację przez API (token z `POST http://localhost:8080/dev/token`) i po kilku sekundach
sprawdź `curl -s 'http://localhost:4566/_aws/ses?email=noreply@chargeflow.pl'`. Znajdź w treści
maila UUID punktu ładowania.

**Ćw. 4 — „cichy sukces”.** Opublikuj na SNS zdarzenie `RESERVATION_CREATED` z
`"customerEmail":""` (szablon w [05](05-messaging-sns-sqs.md), ćw. 2, zmień `eventId`). Sprawdź:
w logach `[EMAIL] Cannot send ... recipient email is empty`, w `/_aws/ses` brak nowego maila,
a w DynamoDB status… `SENT`.

**Ćw. 5 (tylko czytanie, bez kosztów)** — `cd infrastructure/environments/dev && terraform plan`
(wymaga skonfigurowanego backendu i credentiali) i znajdź `aws_sesv2_email_identity.sender`.
Zastanów się, co trzeba zrobić *poza* Terraformem, żeby maile realnie doszły do klientów.

## 8. Pytania kontrolne

1. Co oznacza sandbox SES i jak wpływa na ChargeFlow w dev?
   <details><summary>Odpowiedź</summary>Nowe konto (per region) może wysyłać tylko do zweryfikowanych adresów, max 200 maili/dobę i 1/s. W dev maile do zwykłych klientów zakończą się `MessageRejected` → status FAILED i `NOTIFICATION_FAILED`. Trzeba zweryfikować adresy testowe albo złożyć wniosek o production access.</details>

2. Czy `terraform apply` modułu `ses` wystarczy, żeby wysyłać maile z AWS?
   <details><summary>Odpowiedź</summary>Nie. Tworzy identity w stanie pending; AWS wysyła link weryfikacyjny na adres nadawcy, który ktoś musi kliknąć. Dodatkowo konto jest w sandboxie. Lokalnie (LocalStack) identity jest od razu zweryfikowana, więc problem nie wychodzi.</details>

3. Jakich uprawnień IAM potrzebuje notification-service do wysyłki i co można w nich poprawić?
   <details><summary>Odpowiedź</summary>`ses:SendEmail` (dla SES v2 `SendEmail`; `ses:SendRawEmail` jest dla wysyłki raw). Obecnie `Resource = "*"` i rola współdzielona z charging-session-service. Lepiej: osobna rola dla notification-service i zasób = ARN identity (output `email_identity_arn`), ewentualnie warunek na `ses:FromAddress`.</details>

4. Dlaczego udane wywołanie `SendEmail` nie oznacza, że klient dostał maila?
   <details><summary>Odpowiedź</summary>SES tylko przyjmuje wiadomość do wysyłki. Bounce, complaint czy odrzucenie przez serwer odbiorcy przychodzą asynchronicznie, przez configuration set → SNS/EventBridge. ChargeFlow tego nie konfiguruje, więc status SENT znaczy „przyjęte przez SES”, a powód `SES_BOUNCE` z kontraktu nigdy nie jest używany.</details>

5. Jak sprawdzić lokalnie, co „wysłał” serwis, skoro LocalStack nie dostarcza maili?
   <details><summary>Odpowiedź</summary>Endpoint wewnętrzny LocalStack `GET http://localhost:4566/_aws/ses` (opcjonalnie `?email=<nadawca>`) zwraca zapisane wiadomości. Pamiętaj, że domyślnie `start-local.sh` ustawia `SES_ENABLED=false`, więc trzeba włączyć SES ręcznie.</details>
