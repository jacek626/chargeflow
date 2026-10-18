# Nauka AWS na projekcie ChargeFlow

Ten folder to kurs AWS oparty na prawdziwym kodzie ChargeFlow. Każdy plik opisuje jeden obszar:
co to za usługa, jak jest skonfigurowana w Terraformie, jak korzysta z niej kod Javy/Angulara,
na co uważać i jak to przećwiczyć.

## Jak czytać

Każdy plik ma ten sam układ:

1. **Po co to jest** — intuicja.
2. **Kluczowe pojęcia AWS** — minimum teorii.
3. **Jak to działa w ChargeFlow** — przejście po kodzie z odnośnikami `plik:linia`.
4. **Diagram**.
5. **Lokalnie vs AWS (dev)**.
6. **Koszty i pułapki**.
7. **Ćwiczenia** — najlepiej robić je lokalnie (LocalStack, `terraform plan`), bo to nic nie kosztuje.
8. **Pytania kontrolne** — z odpowiedziami ukrytymi pod „Odpowiedź”.

## Wielki obraz — co się dzieje, gdy kierowca rezerwuje ładowarkę

```
 Przeglądarka
     │ 1. pobiera aplikację Angular            ┌────────────────────────┐
     ├────────────────────────────────────────►│ CloudFront → S3        │  (09)
     │                                         └────────────────────────┘
     │ 2. loguje się (OAuth2 + PKCE)           ┌────────────────────────┐
     ├────────────────────────────────────────►│ Cognito → JWT          │  (08)
     │                                         └────────────────────────┘
     │ 3. POST /reservations  + Bearer JWT
     ▼
 ┌────────┐   ┌──────────────────────────────────────┐
 │  ALB   │──►│ Charging Session Service (ECS)       │  (03)
 └────────┘   │  ├─ zapis rezerwacji ──► RDS Postgres │  (04)
              │  └─ w tej samej transakcji: wiersz   │
              │     w tabeli outbox                  │  (05)
              └───────────────┬──────────────────────┘
                              │ 4. poller czyta outbox i publikuje
                              ▼
                         ┌─────────┐      ┌─────────┐   (po N nieudanych
                         │   SNS   │─────►│   SQS   │──► próbach) DLQ
                         └─────────┘      └────┬────┘           (05)
                                               │ 5. konsument
                                               ▼
              ┌──────────────────────────────────────┐
              │ Notification Service (ECS)           │  (03)
              │  ├─ historia/idempotencja ► DynamoDB │  (06)
              │  └─ e-mail do kierowcy ───► SES      │  (07)
              └──────────────────────────────────────┘

 Wszystko żyje w VPC (02), jest opisane w Terraformie (01),
 wdrażane przez GitHub Actions z rolą IAM (10), a logi i trace'y trafiają do CloudWatch (11).
```

## Spis treści

| # | Plik | Usługi AWS | Proponowana kolejność |
|---|---|---|---|
| 12 | [LocalStack — praca lokalna](12-localstack-lokalnie.md) | LocalStack, docker compose | **zacznij tutaj** — środowisko do ćwiczeń |
| 01 | [Terraform](01-terraform.md) | S3 (stan), moduły, środowiska | 2 |
| 05 | [Messaging: SNS + SQS + Outbox](05-messaging-sns-sqs.md) | SNS, SQS, DLQ | 3 — serce architektury |
| 06 | [DynamoDB](06-dynamodb.md) | DynamoDB | 4 |
| 07 | [SES — e-maile](07-ses.md) | SES | 5 |
| 02 | [Sieć: VPC](02-siec-vpc.md) | VPC, subnety, IGW, NAT, Security Groups | 6 |
| 03 | [ECR + ECS Fargate + ALB](03-ecr-ecs-alb.md) | ECR, ECS, ALB | 7 |
| 04 | [RDS + Secrets Manager](04-rds-secrets-manager.md) | RDS, Secrets Manager, KMS | 8 |
| 08 | [Cognito + JWT](08-cognito-jwt.md) | Cognito | 9 |
| 09 | [Frontend: S3 + CloudFront](09-frontend-s3-cloudfront.md) | S3, CloudFront | 10 |
| 10 | [IAM + CI/CD](10-iam-cicd-github-actions.md) | IAM, STS, OIDC, GitHub Actions | 11 |
| 11 | [Monitoring](11-monitoring-cloudwatch-otel.md) | CloudWatch, OpenTelemetry | 12 |

Numery plików odpowiadają warstwom (od infrastruktury do aplikacji). Kolejność nauki
w ostatniej kolumnie jest inna: najpierw to, co da się przećwiczyć lokalnie za darmo.

## Czego (jeszcze) nie ma

W `infrastructure/modules/` są puste katalogi `api-gateway`, `lambda`, `kms`, `route53`.
To usługi zaplanowane (`infrastructure/CLAUDE.md`), ale niezaimplementowane w V1.

## Przygotowanie

```bash
brew install awscli terraform
docker compose up -d          # Postgres + LocalStack
aws --endpoint-url=http://localhost:4566 sqs list-queues   # test, czy LocalStack odpowiada
```

LocalStack nie wymaga prawdziwych kluczy, wystarczą dowolne, np.
`export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=eu-central-1`
(region domyślny: `infrastructure/environments/local/variables.tf:20`).

> **Ważne przy `terraform apply` w `environments/local`:** provider nie ma bloku `endpoints`,
> do LocalStacka trafia tylko przez zmienną `AWS_ENDPOINT_URL=http://localhost:4566`.
> Bez niej i z aktywnym `AWS_PROFILE` zasoby powstałyby na prawdziwym koncie AWS.
> Szczegóły w [12-localstack-lokalnie.md](12-localstack-lokalnie.md).

> Środowisko `dev` na prawdziwym AWS kosztuje także wtedy, gdy nic nie robi (NAT Gateway, ALB, RDS).
> Zanim cokolwiek zaaplikujesz, ustaw w konsoli AWS **Budget z alertem** i pamiętaj o `terraform destroy`.
