# ChargeFlow

## Event-Driven EV Charging Platform on AWS

ChargeFlow is a production-inspired portfolio project demonstrating Java, Spring Boot, Angular, AWS, Terraform, event-driven architecture, CI/CD and distributed systems.

> ChargeFlow is maintained as a portfolio monorepo for discoverability. Each microservice remains independently buildable, testable, containerized and deployable.

---

## Overview

ChargeFlow simulates a real-world EV charging platform: drivers can browse charging stations, make reservations, start and stop charging sessions, and receive notifications — all backed by event-driven microservices running on AWS.

---

## Current Version

**V1 — Complete**

V1 delivers a fully functioning EV charging platform:

- Charging Session Service (Java 25 / Spring Boot 4.1.1) — station catalog, reservations, sessions, outbox, observability
- Notification Service (Java 25 / Spring Boot 4.1.1) — SQS consumer, DynamoDB persistence, SES email, SNS result events
- Angular 21 frontend — customer flow (browse, reserve, charge) + operator dashboard
- Terraform infrastructure — all AWS services provisioned as reusable modules
- GitHub Actions CI + CD — automated tests, build, deploy to AWS DEV

---

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for the current high-level design.

The system uses an event-driven approach based on SNS + SQS with a Transactional Outbox pattern to ensure reliable message delivery.

---

## Technology Stack

| Layer | Technology |
|---|---|
| Backend | Java 25, Spring Boot 4.1.1, Maven |
| Frontend | Angular 21 |
| Messaging | SNS, SQS |
| Database (sessions) | PostgreSQL 16 (RDS) |
| Database (notifications) | DynamoDB |
| Auth | Cognito, OAuth2 PKCE |
| Email | SES |
| Infrastructure | Terraform 1.11 |
| CI/CD | GitHub Actions |
| Containers | Docker, ECS Fargate |
| CDN | S3 + CloudFront |
| Observability | CloudWatch, OpenTelemetry |

---

## Repository Structure

```
chargeflow/
├── charging-session-service/   # Java Spring Boot — session management
├── notification-service/       # Java Spring Boot — notifications
├── frontend/                   # Angular SPA
├── infrastructure/             # Terraform modules and environments
│   ├── bootstrap/              # S3 + DynamoDB for Terraform state
│   ├── modules/                # Reusable modules (network, ecr, ecs, rds, alb, …)
│   └── environments/dev/       # DEV environment wiring
├── integration-tests/          # Cross-service integration tests
├── docs/                       # Architecture, specs, decisions, API contracts
└── .github/workflows/          # CI + CD pipelines
```

---

## Project Status

**V1 — Complete**

| Component | Status |
|---|---|
| Repository structure | Done |
| Charging Session Service — domain | Done (stations, reservations, sessions, expiration, history) |
| Charging Session Service — security | Done (Cognito JWT, RBAC) |
| Charging Session Service — observability | Done (Micrometer metrics, OTel tracing, Prometheus, structured logs) |
| Notification Service — consumer | Done (SQS, 5 event types) |
| Notification Service — persistence | Done (DynamoDB, idempotent) |
| Notification Service — email | Done (SES, 5 email templates) |
| Notification Service — result events | Done (SNS NOTIFICATION_SENT/FAILED) |
| Transactional Outbox | Done |
| Angular customer flow | Done |
| Angular operator dashboard | Done |
| End-to-end integration tests | Done (Testcontainers, 7 E2E scenarios) |
| Docker Compose (local infra) | Done — PostgreSQL + LocalStack |
| Terraform — network, ECR, RDS, ALB | Done |
| Terraform — ECS Fargate | Done |
| Terraform — S3 + CloudFront | Done |
| Terraform — Cognito, SES, DynamoDB, messaging | Done |
| Terraform — state bootstrap | Done |
| GitHub Actions CI | Done (test + build + validate-terraform) |
| GitHub Actions CD | Done (Docker build+push, terraform apply, S3 sync, CF invalidation) |

---

## Local Development

Requirements:

- Docker Desktop
- Java 25 (e.g. via Homebrew: `brew install openjdk@25`)
- Node.js 20.19+ (via nvm: `nvm install 20.19.5`; Node 22.12+ / 24 also supported)
- Angular CLI 21 (`npm install -g @angular/cli@21`)
- Maven 3.9+
- Terraform 1.11+

### Start local infrastructure

```bash
cp .env.example .env
docker compose up -d
docker compose ps
```

PostgreSQL is available at `localhost:5432` (database: `chargeflow`, user: `chargeflow`).
LocalStack emulates AWS at `http://localhost:4566`.

### Run backend services locally

```bash
export JAVA_HOME=/path/to/java25   # e.g. /opt/homebrew/opt/openjdk@25 on macOS

# Charging Session Service (port 8080)
cd charging-session-service && ./mvnw spring-boot:run

# Notification Service (port 8081)
cd notification-service && ./mvnw spring-boot:run
```

### Run the Angular frontend

```bash
cd frontend
nvm use 24
npm install
npm start   # http://localhost:4200
```

### Run tests

```bash
# Backend — requires Docker (Testcontainers)
cd charging-session-service && ./mvnw test
cd notification-service     && ./mvnw test

# Frontend
cd frontend && npm test -- --watch=false
```

---

## CI/CD

### CI — `.github/workflows/ci.yml`

Runs on every push to `main` and on pull requests targeting `main`:

- `test-charging-session-service` — Java 25, `./mvnw test -B`
- `test-notification-service` — Java 25, `./mvnw test -B`
- `build-frontend` — Node 20, `npm ci` + `npx ng build --configuration=production`
- `validate-terraform` — `terraform fmt -check -recursive` + `terraform validate`

### CD — `.github/workflows/deploy.yml`

Runs on push to `main` (and manually via `workflow_dispatch`). Requires a GitHub environment named `dev`.

**Required GitHub repository secrets:**

| Secret | Description |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | IAM role ARN output by `terraform output github_deploy_role_arn` |
| `TF_STATE_BUCKET` | S3 bucket name created by `infrastructure/bootstrap/` |
| `TF_LOCK_TABLE` | DynamoDB table name created by `infrastructure/bootstrap/` |

**First-time setup:**

```bash
# 1. Bootstrap Terraform state storage (one-time, run manually)
cd infrastructure/bootstrap
terraform init && terraform apply

# 2. Deploy DEV infrastructure (first time — creates OIDC role, ECR, etc.)
cd infrastructure/environments/dev
terraform init \
  -backend-config="bucket=<state-bucket>" \
  -backend-config="region=eu-central-1" \
  -backend-config="dynamodb_table=<lock-table>"
terraform apply -var "github_repo=<your-org/chargeflow>"

# 3. Set AWS_DEPLOY_ROLE_ARN in GitHub repository secrets
terraform output github_deploy_role_arn

# 4. Set TF_STATE_BUCKET and TF_LOCK_TABLE secrets, then push to main
```

---

## Roadmap

| Version | Scope |
|---|---|
| V1 | Session service, notification service, Angular frontend, Terraform, CI/CD — **complete** |
| V2 | Station Service, Payment Service, Kafka/MSK |
| V3 | Analytics Service, Kubernetes/EKS, ML, Bedrock RAG, AI agents |
