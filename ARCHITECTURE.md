# Architecture — ChargeFlow V1

## System Flow

```
Angular (SPA)
   |
   | HTTPS / REST
   v
ALB (Application Load Balancer)
   |
   v
Charging Session Service  (ECS Fargate)
   |
   +--> RDS PostgreSQL
   |
   +--> Transactional Outbox (PostgreSQL table)
            |
            | Outbox poller
            v
           SNS Topic
            |
            v
           SQS Queue
            |
            v
   Notification Service  (ECS Fargate)
            |
            +--> DynamoDB  (notification history)
            |
            +--> SES  (email delivery)
```

## Authentication

All API requests are authenticated via AWS Cognito (OAuth2 / JWT).

```
Angular  --(OAuth2 Authorization Code + PKCE)-->  Cognito
Angular  --(Bearer JWT)-->  Charging Session Service
Charging Session Service  --(validates JWT)-->  Cognito JWKS
```

## Deployment Model

| Component | AWS Service |
|---|---|
| Frontend | S3 + CloudFront |
| Backend | ALB + ECS Fargate |
| Session DB | RDS PostgreSQL |
| Notification store | DynamoDB |
| Messaging | SNS + SQS (+ DLQ) |
| Auth | Cognito |
| Email | SES |
| Container registry | ECR |
| Secrets | Secrets Manager |
| Monitoring | CloudWatch + OpenTelemetry |
| Infrastructure | Terraform |
| CI/CD | GitHub Actions |

## Reliability

- Transactional Outbox ensures no events are lost between DB commit and SNS publish.
- SQS DLQ captures messages that fail after maximum retries.
- Notification consumers are idempotent — duplicate `eventId` does not trigger duplicate emails.

## Security

- No credentials in code or Terraform state files.
- All secrets stored in AWS Secrets Manager.
- VPC isolates backend services from the public internet.
- JWT validation on every protected API endpoint.

## Notes

- V2/V3 components (Station Service, Payment Service, Kafka, EKS, Analytics, Bedrock) are NOT part of V1.
- This document describes the current implemented architecture only.
