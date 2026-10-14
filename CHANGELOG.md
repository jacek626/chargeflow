# Changelog

## [Unreleased]

### Changed — Frontend downgraded to Angular 21 for Node 20 compatibility (2026-10-14)

Angular 22 requires Node `^22.22.3 || ^24.15.0 || >=26`, so it did not run on Node 20.19.5 (also used by CI/CD workflows).

- `frontend/package.json` — Angular `^22.1.x` → `^21.2.0` (resolved 21.2.25 / CLI 21.2.26), TypeScript `~6.0.2` → `~5.9.0` (Angular 21 requires `<6.0`), `packageManager` → `npm@10.8.2`, added `engines.node` `^20.19.0 || ^22.12.0 || >=24.0.0`
- `frontend/package-lock.json` — regenerated
- `frontend/.nvmrc` — `24` → `20.19.5`
- `README.md`, `frontend/README.md`, `.github/workflows/ci.yml` — version references updated

## [V1.0.0] — 2026-10-08

### Added — CF-030 | DONE | V1 Final Verification and Documentation (2026-10-08)

**`README.md` fully updated:**
- Project status table reflects all 20 completed V1 components
- Technology stack updated with exact versions (Spring Boot 4.1.1, Angular 22, Terraform 1.11, PostgreSQL 16)
- Repository structure updated with `bootstrap/` and `environments/dev/`
- CI/CD section expanded: CI jobs documented; CD section added with required GitHub secrets, first-time setup instructions (bootstrap → terraform apply → set secrets → push)
- Version updated from "V1 — Initial project setup" to "V1 — Complete"
- Roadmap updated marking V1 as complete

**All 30 backlog tasks complete — ChargeFlow V1 is done.**

### Added — CF-029 | DONE | Deploy ChargeFlow V1 to AWS DEV (2026-10-08)

**`infrastructure/bootstrap/` (new):**
- `main.tf` — S3 bucket (versioned, AES256, public-access-blocked) + DynamoDB table (`LockID`, PAY_PER_REQUEST) for remote Terraform state
- `variables.tf` — `aws_region`, `state_bucket_name`, `lock_table_name`
- `outputs.tf` — `state_bucket_name`, `lock_table_name`
- `versions.tf` — AWS + local provider blocks

**`infrastructure/modules/iam-github/` (new):**
- `main.tf` — GitHub Actions OIDC provider (token.actions.githubusercontent.com); `deploy` IAM role with `AssumeRoleWithWebIdentity` trust limited to `repo:${var.github_repo}:*`; `AdministratorAccess` attachment
- `variables.tf` — `name_prefix`, `github_repo`
- `outputs.tf` — `deploy_role_arn`

**`infrastructure/environments/dev/` updated:**
- `backend.tf` — S3 backend with `key = "dev/terraform.tfstate"`; bucket/region/DynamoDB table passed at init time via `-backend-config` (not hardcoded)
- `main.tf` — `iam_github` module prepended
- `variables.tf` — added `github_repo`
- `outputs.tf` — added `github_deploy_role_arn`
- `terraform.tfvars.example` — added `github_repo = "your-org/chargeflow"`

**`.github/workflows/deploy.yml` (new):**
- Triggers on push to `main` and `workflow_dispatch`
- `permissions: { id-token: write, contents: read }` for OIDC token exchange
- AWS credentials via `aws-actions/configure-aws-credentials@v4` with `role-to-assume: ${{ secrets.AWS_DEPLOY_ROLE_ARN }}` (no long-lived keys)
- ECR login → build + push `charging-session-service` image (`chargeflow-dev-charging-session-service:${{ github.sha }}`)
- ECR login → build + push `notification-service` image (`chargeflow-dev-notification-service:${{ github.sha }}`)
- Angular build: `npm ci` + `npx ng build --configuration=production` (output: `dist/chargeflow-web/browser/`)
- `terraform init` with `-backend-config` flags from `secrets.TF_STATE_BUCKET` / `secrets.TF_LOCK_TABLE`
- `terraform apply -auto-approve -var "charging_session_image=..." -var "notification_image=..." -var "github_repo=..."`
- Frontend deploy: `aws s3 sync … --delete` with long-lived cache headers for assets + `no-cache` for `index.html`
- CloudFront invalidation: `aws cloudfront create-invalidation --paths "/*"`
- Step summary with ALB URL and CloudFront domain

**Required GitHub repository configuration:**
- Secrets: `AWS_DEPLOY_ROLE_ARN`, `TF_STATE_BUCKET`, `TF_LOCK_TABLE`
- Environment: `dev` (for deployment gate)

### Added — CF-028 | DONE | AWS DEV Infrastructure (2026-10-08)

**New Terraform modules:**

- `modules/network` — VPC (10.0.0.0/16), 2 public + 2 private subnets across 2 AZs, IGW, single NAT gateway, 3 security groups (ALB, ECS, RDS)
- `modules/ecr` — Two ECR repositories (`charging-session-service`, `notification-service`) with scan-on-push and lifecycle policy (keep last 10 images)
- `modules/rds` — RDS PostgreSQL 16 (db.t4g.micro, single-AZ, gp3, encrypted), random password stored in Secrets Manager
- `modules/alb` — Internet-facing ALB with HTTP listener; optional HTTPS listener when ACM cert ARN is provided; health-check target group for charging-session-service on port 8080
- `modules/ecs` — ECS Fargate cluster with Container Insights; execution role + task role with least-privilege IAM; task definitions and services for both services; environment variables wired from all other modules; `ignore_changes = [task_definition]` for CD deployments
- `modules/frontend` — S3 (private, versioned) + CloudFront with OAC; SPA routing (404/403 → index.html); optional custom domain + ACM cert

**`environments/dev` updated:**
- `main.tf` — all 8 modules wired with cross-module references
- `variables.tf` — added container image URIs, ECS sizing, TLS cert ARNs, domain aliases
- `outputs.tf` — 20+ outputs covering networking, ECR, ECS, frontend, messaging, auth, RDS, DynamoDB
- `terraform.tfvars.example` — updated with new variable examples
- `versions.tf` — added `random` provider (~> 3.6)

`terraform fmt -check -recursive` and `terraform validate` pass.

### Added — CF-027 | DONE | GitHub Actions CI (2026-10-08)

**`.github/workflows/ci.yml`:**
- Triggers on push to `main` and on pull requests targeting `main`
- `test-charging-session-service` — Java 25 (Temurin), `./mvnw test -B`, Maven cache, Docker available for Testcontainers
- `test-notification-service` — same setup, runs independently in parallel
- `build-frontend` — Node 20, `npm ci`, `npx ng build --configuration=production`, npm cache
- `validate-terraform` — Terraform 1.11, `terraform fmt -check -recursive`, `terraform init -backend=false` + `terraform validate` on dev environment
- Fixed `infrastructure/modules/cognito/main.tf` formatting so `terraform fmt -check` passes

### Added — CF-026 | DONE | End-to-End Integration Tests (2026-10-08)

**charging-session-service — `CustomerJourneyE2ETest` (7 tests):**
- Full-stack tests: HTTP → Controller → Service → Repository → PostgreSQL (Testcontainers)
- `fullCustomerJourney_reserve_start_stop_completesSuccessfully` — complete customer flow via HTTP
- `fullCustomerJourney_afterStop_chargingPointIsAvailableForNextCustomer` — point released after session stop
- `expiredReservation_startSession_returns409WithExpiredCode` — 409 RESERVATION_EXPIRED via HTTP
- `cancelledReservation_startSession_returns409` — 409 on cancelled reservation via HTTP
- `createReservation_withoutToken_returns401` — unauthenticated request rejected
- `stopSession_wrongCustomer_returns403` — cross-customer access denied via HTTP
- `concurrentReservations_samePoint_exactlyOneSucceeds` — spec-required concurrent HTTP test (CF-026 acceptance criterion)
- 199 tests ✓

### Added — CF-025 | DONE | Observability and Distributed Tracing (2026-10-08)

**charging-session-service:**
- Added `micrometer-tracing-bridge-otel` and `micrometer-registry-prometheus` dependencies
- Structured JSON logging (ECS format) via `logging.structured.format.console: ecs`
- Actuator endpoints: health, info, metrics, prometheus; tracing sampling at 100%
- `ReservationService` — counters: `chargeflow.reservations.created`, `chargeflow.reservations.cancelled`
- `ChargingSessionService` — counters: `chargeflow.sessions.started`, `chargeflow.sessions.completed`
- `ReservationExpirationJob` — counter: `chargeflow.reservations.expired`
- 192 tests ✓

**notification-service:**
- Added `micrometer-tracing-bridge-otel` and `micrometer-registry-prometheus` dependencies
- Structured JSON logging (ECS format)
- Actuator endpoints: health, info, metrics, prometheus
- `DomainEventRouter` — counters: `chargeflow.notifications.sent`, `chargeflow.notifications.failed` (per eventType)
- 13 tests ✓

**infrastructure:**
- `modules/monitoring/main.tf` — CloudWatch log groups for both services (`/chargeflow/{env}/charging-session-service`, `/chargeflow/{env}/notification-service`)
- `modules/monitoring/variables.tf` — `name_prefix`, `environment`, `log_retention_days` (default 30)
- `modules/monitoring/outputs.tf` — log group name outputs
- `environments/local/main.tf` — monitoring module with 7-day retention
- `environments/dev/main.tf` — monitoring module with configurable retention

### Added — CF-024 | DONE | Operator Angular Flow (2026-10-08)

**charging-session-service — admin read endpoints:**
- `ChargingStationRepository` — added `findAllByOrderByName()`
- `ChargingPointRepository` — added `findAllByStationId()`, `findAllByStationIds()` (no active filter)
- `ChargingReservationRepository` — added `findAllByOrderByReservedAtDesc()`
- `ChargingSessionRepository` — added `findAllByOrderByStartedAtDesc()`
- `ChargingStationService` — added `findAllForAdmin()` (all stations), `findByIdForAdmin()` (any station + all points)
- `ReservationService` — added `listAllReservations()` (no customerId filter)
- `ChargingSessionService` — added `listAllSessions()` (no customerId filter)
- `StationAdminController` — `GET /api/admin/stations`, `GET /api/admin/stations/{stationId}`
- `ReservationAdminController` — `GET /api/admin/reservations`
- `ChargingSessionAdminController` — `GET /api/admin/charging-sessions`
- 192 tests ✓

**frontend — operator flow:**
- `auth/operator.guard.ts` — `operatorGuard`: checks authentication + `cognito:groups` includes 'OPERATOR'; redirects unauthenticated to Cognito, unauthorized to `/stations`
- `operator/admin.service.ts` — HTTP client for all 5 admin endpoints (list/get stations, reservations, sessions, PATCH point status)
- `operator/operator-dashboard/operator-dashboard.ts` — summary stats grid (stations, points, reservations, sessions counts); quick-links to manage/view screens
- `operator/operator-stations/operator-stations.ts` — expandable station list; click to load charging points; inline "Mark OOS" / "Restore" buttons via `PATCH /api/admin/charging-points/{id}/status`
- `operator/operator-reservations/operator-reservations.ts` — all reservations table (customer, station, point, status, dates)
- `operator/operator-sessions/operator-sessions.ts` — all sessions table (customer, station, point, status, energy, cost)
- `app.routes.ts` — added `/admin`, `/admin/stations`, `/admin/reservations`, `/admin/sessions` (all guarded by `operatorGuard`)
- `shared/navbar/navbar.ts` — added `isOperator` getter; shows Dashboard + Manage links when user has OPERATOR group
- TypeScript: 0 errors ✓

### Added — CF-021 | DONE | Notification Result Events (2026-10-07)

**notification-service:**
- `pom.xml` — added `software.amazon.awssdk:sns`
- `config/SnsConfig.java` — conditional `SnsClient` bean (`@ConditionalOnProperty chargeflow.sns.enabled=true`); `endpoint-url` override for LocalStack
- `publisher/NotificationResultPublisher.java` — interface: `publishSent(notificationId, customerId, triggerEventType)` / `publishFailed(…, reason)`
- `publisher/SnsNotificationResultPublisher.java` — SNS implementation (`@ConditionalOnProperty … havingValue="true"`); builds `NOTIFICATION_SENT` / `NOTIFICATION_FAILED` envelope with new `eventId`, `occurredAt=now`, `aggregateId=notificationId`; catches all SNS exceptions internally (fire-and-forget, never blocks handler retry logic)
- `publisher/LoggingNotificationResultPublisher.java` — no-op fallback (`@ConditionalOnProperty havingValue="false", matchIfMissing=true`); logs at DEBUG
- All 5 handlers — inject `NotificationResultPublisher`; call `publishSent()` after `updateStatus(SENT)`, `publishFailed()` after `updateStatus(FAILED)` before rethrowing
- `application.yml` — added `chargeflow.sns.enabled`, `chargeflow.sns.notification-topic-arn`
- 13 tests ✓

### Added — CF-020 | DONE | SES Email Notifications (2026-10-07)

**charging-session-service — customerEmail propagation:**
- `db/migration/V6__add_customer_email_to_reservation.sql` — Flyway: `ALTER TABLE charging_reservation ADD COLUMN customer_email VARCHAR(255) NOT NULL DEFAULT ''`
- `ChargingReservation` entity — added `customerEmail` field, getter, and 3-arg constructor `(customerId, customerEmail, ChargingPoint, reservedAt, validUntil)`
- `ReservationService.createReservation(UUID, String customerId, String customerEmail)` — now 3-arg
- `ReservationController.createReservation()` — extracts `email` claim from `Jwt`; passes to service
- All 5 payload records updated with `customerEmail` field: `ReservationCreatedPayload`, `ReservationCancelledPayload`, `ReservationExpiredPayload`, `ChargingStartedPayload`, `ChargingCompletedPayload`
- `OutboxEventService` — all 5 `save*()` methods now forward `reservation.getCustomerEmail()`
- 192 tests ✓

**notification-service — SES integration:**
- `pom.xml` — added `software.amazon.awssdk:sesv2`
- `config/SesConfig.java` — conditional `SesV2Client` bean (`@ConditionalOnProperty chargeflow.ses.enabled=true`); `endpoint-url` override for LocalStack
- `email/EmailServicePort.java` — interface with 5 methods: `sendReservationCreated()`, `sendReservationCancelled()`, `sendReservationExpired()`, `sendChargingStarted()`, `sendChargingCompleted()`
- `email/EmailService.java` — real SES implementation (`@ConditionalOnProperty … havingValue="true"`); sends HTML emails via `SesV2Client.sendEmail()`
- `email/NoOpEmailService.java` — no-op (`@ConditionalOnProperty havingValue="false", matchIfMissing=true`); logs at DEBUG
- All 5 handlers — inject `EmailServicePort`; call appropriate `send*()` after `saveIfNew()`; `updateStatus(SENT)` on success, `updateStatus(FAILED)` + rethrow on failure (SQS redelivery)
- `application.yml` — added `chargeflow.ses.enabled`, `chargeflow.ses.from-address`
- **infrastructure/modules/ses/ (new module):** `aws_sesv2_email_identity` for sender address; wired into `environments/local/main.tf` and `environments/dev/main.tf` + `variables.tf`
- 9 tests ✓

### Added — CF-019 | DONE | Notification Persistence in DynamoDB (2026-10-07)

**infrastructure/modules/dynamodb/ (new module):**
- `aws_dynamodb_table` `chargeflow-{env}-notifications` — PAY_PER_REQUEST billing, PK: `eventId` (S); GSI `customerId-createdAt-index` for future customer history queries
- Wired into `environments/local/main.tf` + `outputs.tf` and `environments/dev/main.tf` + `outputs.tf`
- `terraform validate` ✓ (local + dev)

**notification-service:**
- `pom.xml` — added `software.amazon.awssdk:dynamodb`
- `config/DynamoDbConfig.java` — `DynamoDbClient` bean with `chargeflow.aws.endpoint-url` override for LocalStack
- `notification/NotificationStatus.java` — enum: `PENDING`, `SENT`, `FAILED`
- `notification/NotificationRecord.java` — domain record; `pending()` factory method
- `notification/NotificationRepository.java` — `saveIfNew()` uses conditional `putItem` with `attribute_not_exists(eventId)` → returns false on `ConditionalCheckFailedException` (idempotency); `updateStatus()` for CF-020/CF-021
- `application.yml` — added `chargeflow.dynamodb.table-name: ${DYNAMODB_NOTIFICATIONS_TABLE:chargeflow-local-notifications}`
- All 5 handlers (`ReservationCreated/Cancelled/Expired`, `ChargingStarted/Completed`) — inject `NotificationRepository`; call `saveIfNew()` before logging; duplicate events are silently skipped
- 9 tests ✓

### Added — CF-023 | DONE | Customer Angular Flow (2026-10-07)

**frontend/src/environments/**
- `environment.ts` — Cognito config placeholders (`clientId`, `cognitoDomain`, `region`, `redirectUri`, `postLogoutUri`, `scopes`)

**frontend/src/app/auth/**
- `auth.service.ts` — full PKCE OAuth2 Authorization Code flow: `login()`, `handleCallback()`, `logout()`, `isAuthenticated()`, `getAccessToken()`, `getUserClaims()`; tokens in `sessionStorage`; Web Crypto API for SHA-256 challenge
- `auth.interceptor.ts` — functional `HttpInterceptorFn`; adds `Authorization: Bearer <token>` to all API requests
- `auth.guard.ts` — functional `CanActivateFn`; redirects unauthenticated users to Cognito login
- `auth/callback/callback.ts` — standalone `CallbackComponent`; exchanges code for tokens, redirects to `/stations` on success

**frontend/src/app/ — models and services**
- `stations/station.model.ts` — `ChargingStationSummary`, `ChargingStationDetails`, `ChargingPoint`
- `reservations/reservation.model.ts` — `Reservation`, `CreateReservationRequest`
- `sessions/session.model.ts` — `ChargingSession`, `StartSessionRequest`
- `stations/station.service.ts` — `listStations()`, `getStation(id)`
- `reservations/reservation.service.ts` — `listReservations()`, `getReservation(id)`, `createReservation()`, `cancelReservation(id)`
- `sessions/session.service.ts` — `listSessions()`, `getSession(id)`, `startSession()`, `stopSession(id)`

**frontend/src/app/ — components**
- `shared/navbar/navbar.ts` — `NavbarComponent`; shows nav links, user email, login/logout button
- `stations/station-list/station-list.ts` — `StationListComponent`; lists active stations with availability count
- `stations/station-detail/station-detail.ts` — `StationDetailComponent`; shows charging points, inline reserve button → redirects to reservations
- `reservations/reservation-list/reservation-list.ts` — `ReservationListComponent`; lists own reservations, inline cancel
- `sessions/session-list/session-list.ts` — `SessionListComponent`; lists own sessions, inline stop, shows energy/cost

**frontend/src/app/ — wiring**
- `app.routes.ts` — lazy-loaded routes: `/`, `/auth/callback`, `/stations`, `/stations/:stationId` (guarded), `/reservations` (guarded), `/sessions` (guarded)
- `app.config.ts` — added `provideHttpClient(withInterceptors([authInterceptor]))`
- `app.html` — `<app-navbar>` + `<router-outlet>`
- `app.ts` — added `NavbarComponent` to imports
- TypeScript compile: 0 errors ✓

### Added — CF-022 | DONE | Cognito Authentication (2026-10-07)

**Terraform — infrastructure/modules/cognito/:**
- `aws_cognito_user_pool` — email as username, email verification, password policy
- `aws_cognito_user_group` CUSTOMER + OPERATOR — populate `cognito:groups` JWT claim
- `aws_cognito_user_pool_client` — Angular PKCE public client (no secret, code flow, openid/email/profile scopes, SRP + refresh token auth)
- `aws_cognito_user_pool_domain` — hosted UI domain
- Outputs: `user_pool_id`, `user_pool_arn`, `client_id`, `domain`, `jwks_uri`, `issuer_uri`
- `infrastructure/environments/local/main.tf` + `outputs.tf` — wired with `callback_urls=["http://localhost:4200/auth/callback"]`
- `infrastructure/environments/dev/main.tf` + `outputs.tf` + `variables.tf` — wired with configurable callback/logout URLs
- `terraform validate` ✓ (local + dev)

**charging-session-service — Spring Security:**
- `SecurityConfig` — replaced `denyAll()` with real JWT resource server: `cognitoJwtConverter()` maps `cognito:groups` claim → `ROLE_CUSTOMER` / `ROLE_OPERATOR`; `/api/stations/**` public, `/api/reservations/**` + `/api/charging-sessions/**` require CUSTOMER, `/api/admin/**` requires OPERATOR
- `ReservationController`, `ChargingSessionController` — removed `@RequestHeader X-Customer-Id` placeholder; now use `@AuthenticationPrincipal Jwt jwt` + `jwt.getSubject()` as customerId
- `application.yml` — added `spring.security.oauth2.resourceserver.jwt.jwk-set-uri: ${COGNITO_JWKS_URI:}`
- All 4 controller tests migrated to `SecurityMockMvcRequestPostProcessors.jwt()` + explicit `ROLE_CUSTOMER`/`ROLE_OPERATOR` authorities; added `@Import(SecurityConfig.class)` + `@MockitoBean JwtDecoder jwtDecoder` for proper security wiring in `@WebMvcTest` slice
- All 7 `@SpringBootTest` integration tests + `ChargingSessionServiceApplicationTests` — added `@MockitoBean JwtDecoder jwtDecoder` to satisfy `SecurityConfig` context
- 192 tests ✓

### Added — CF-018 | DONE | Implemented Notification Event Consumer (2026-10-07)

**charging-session-service:**
- `SnsConfig` — conditional `SnsClient` bean (`@ConditionalOnProperty chargeflow.sns.enabled=true`); endpoint URL override for LocalStack
- `SnsDomainEventPublisher` — wraps `OutboxEvent` in full `DomainEvent` JSON envelope (eventId, eventType, eventVersion, occurredAt, aggregateId, payload as embedded object); publishes to SNS topic; throws `RuntimeException` on failure so outbox poller does not mark event as published
- `LoggingDomainEventPublisher` — now `@ConditionalOnProperty chargeflow.sns.enabled=false matchIfMissing=true` (default, active in tests)
- `application.yml` — added `chargeflow.aws`, `chargeflow.sns.{enabled,topic-arn}` properties

**notification-service:**
- `NotificationServiceApplication` — added `@EnableScheduling`
- `SqsConfig` — conditional `SqsClient` bean (`@ConditionalOnProperty chargeflow.sqs.enabled=true`)
- `IncomingEvent` — record: eventId, eventType, eventVersion, occurredAt, aggregateId, `JsonNode payload` (Jackson 3.x)
- `DomainEventHandler` — interface with `eventType()` + `handle(IncomingEvent)`
- 5 handlers in `consumer/handler/`: `ReservationCreatedHandler`, `ReservationCancelledHandler`, `ReservationExpiredHandler`, `ChargingStartedHandler`, `ChargingCompletedHandler` — log event fields; SES wiring deferred to CF-020
- `DomainEventRouter` — dispatches `IncomingEvent` to registered handler by `eventType`; unknown types silently skipped
- `SqsEventConsumer` — conditional `@Scheduled` poller (fixedDelay=100ms + SQS long poll 20s); `maxNumberOfMessages=10`, `visibilityTimeout=30s`; deletes only on success — failed messages stay visible for SQS retry/DLQ
- `application.yml` — added `chargeflow.aws`, `chargeflow.sqs.{enabled,queue-url,...}` properties; `spring.jackson.datatype.datetime.write-dates-as-timestamps: false`
- 7 new tests: 4 `SnsDomainEventPublisherTest` (topic ARN, envelope fields, SNS failure, payload embedded as object), 3 `DomainEventRouterTest` (routing, unknown type, multi-handler), 4 `SqsEventConsumerTest` (route+delete, handler throws→no delete, invalid JSON→no delete, empty queue)
- Total: charging-session-service 188 tests ✓, notification-service 9 tests ✓

### Added — CF-017 | DONE | Provisioned Local Messaging Infrastructure (2026-10-07)

- `infrastructure/modules/messaging/` — reusable Terraform module: `aws_sns_topic` (domain events), `aws_sqs_queue` (notifications, 30 s visibility timeout, 24 h retention, DLQ redrive after 3 failures), `aws_sqs_queue.notifications_dlq`, `aws_sqs_queue_policy` (AllowSNSPublish), `aws_sns_topic_subscription` (sqs, raw delivery)
- `infrastructure/environments/local/main.tf` — wires messaging module; resource names: `chargeflow-local-domain-events`, `chargeflow-local-notifications`, `chargeflow-local-notifications-dlq`
- `infrastructure/environments/local/outputs.tf` — exposes all 6 outputs: `sns_topic_arn/name`, `sqs_queue_arn/url`, `sqs_dlq_arn/url`
- `infrastructure/environments/dev/main.tf` + `outputs.tf` — same module wired for real AWS (credentials via env vars / IAM role, no hardcoded values)
- All `versions.tf` updated from `~> 1.16.2` to `~> 1.11` to match installed Terraform 1.11.4
- `terraform fmt -check -recursive` ✓, `terraform validate` ✓ (both local and dev)
- `terraform plan` for local shows 5 resources to add (apply requires running the command manually against LocalStack)

### Added — CF-015 | DONE | Implemented Transactional Outbox (2026-10-06)

- `db/migration/V5__create_outbox_event.sql` — `outbox_event` table: UUID PK, `aggregate_id`, `aggregate_type` (VARCHAR 20), `event_type` (VARCHAR 30), `payload` (TEXT), `created_at` (TIMESTAMPTZ), `published_at` (TIMESTAMPTZ nullable), `status` CHECK ('NEW','PUBLISHED'); partial index `idx_outbox_event_new` on `created_at WHERE status = 'NEW'` for fast polling
- `OutboxEventStatus` — enum: NEW, PUBLISHED
- `OutboxAggregateType` — enum: RESERVATION, SESSION
- `OutboxEvent` — JPA entity; constructor sets `status=NEW`, `createdAt=Instant.now()`; `markPublished()` sets status + `publishedAt`
- `OutboxEventRepository` — `findUnpublishedBatch(limit)` native query with `FOR UPDATE SKIP LOCKED` for safe concurrent polling
- `OutboxEventService` — serializes domain state to JSON payload (Jackson 3.x `tools.jackson.databind.ObjectMapper`) and saves outbox record; 6 methods: `saveReservationCreated`, `saveReservationCancelled`, `saveReservationExpired`, `saveChargingStarted`, `saveChargingCompleted`, `saveChargingFailed`
- `DomainEventPublisher` — interface `void publish(OutboxEvent)`
- `LoggingDomainEventPublisher` — default `@Component` implementation (logs payload; real SNS in CF-017)
- `OutboxPoller` — `@Scheduled(fixedDelayString)` polls unpublished batch (100), calls publisher, marks published in same TX; `FOR UPDATE SKIP LOCKED` ensures safe multi-instance deployment
- `ReservationService` — injects `OutboxEventService`; saves `RESERVATION_CREATED` after create, `RESERVATION_CANCELLED` after cancel
- `ReservationExpirationJob` — injects `OutboxEventService`; saves `RESERVATION_EXPIRED` for each expired reservation
- `ChargingSessionService` — injects `OutboxEventService`; saves `CHARGING_STARTED` after session created, `CHARGING_COMPLETED` after stop
- 16 new tests: 7 unit (`OutboxEventServiceTest` — payload JSON correctness, event types, status=NEW), 4 unit (`OutboxPollerTest` — publisher called, events marked PUBLISHED, empty-batch guard), 5 integration (`OutboxIntegrationTest` — atomicity of reservation/session outbox writes, poller marks PUBLISHED, assertions scoped by aggregateId)
- Total: 184 tests, all passing with Docker

### Added — CF-016 | DONE | Defined Domain Event Contracts (2026-10-06)

- `docs/contracts/events/README.md` — complete event catalog: envelope spec + 8 event types with JSON examples and field descriptions (6 produced by Charging Session Service, 2 produced by Notification Service)
- `charging-session-service` — new package `pl.chargeflow.session.event`: `DomainEventType` enum, `DomainEvent` envelope record, 6 payload records (`ReservationCreatedPayload`, `ReservationCancelledPayload`, `ReservationExpiredPayload`, `ChargingStartedPayload`, `ChargingCompletedPayload`, `ChargingFailedPayload`)
- `notification-service` — new package `pl.chargeflow.notification.event`: same `DomainEventType` + `DomainEvent`, same 6 consumed payload records, plus `NotificationSentPayload` and `NotificationFailedPayload`
- Both services compile cleanly; contracts are independent copies per service (no shared module coupling)

### Added — CF-012 / CF-013 / CF-014 | DONE | Implemented Charging Sessions (2026-10-06)

- `db/migration/V4__create_charging_session.sql` — `charging_session` table (UUID PK, FK to `charging_point` and `charging_reservation`, status CHECK, `price_per_kwh` snapshot, `energy_consumed_kwh`, `total_cost`, timestamps, optimistic lock `version`); indexes on `customer_id`, `charging_point_id`, `status`
- `SessionStatus` — enum: STARTED, CHARGING, COMPLETED, FAILED
- `ChargingSession` — JPA entity; constructor snapshots `pricePerKwh` from point; `complete(energyKwh)` — calculates `totalCost = energyKwh × pricePerKwh` (HALF_UP, scale 2), sets stoppedAt; `fail()`; `isActive()` (STARTED or CHARGING); both state transitions guarded by `InvalidChargingSessionStatusTransitionException`
- `ChargingPoint` — added `startCharging()` (RESERVED→CHARGING) and `stopCharging()` (CHARGING→AVAILABLE); both guarded by `InvalidChargingPointStatusTransitionException`
- `ChargingReservation` — added `markUsed()` (active→USED) and `expire()` (active→EXPIRED); both guarded by `InvalidReservationStatusTransitionException`
- `ChargingSessionRepository` — Spring Data JPA; `findByCustomerIdOrderByStartedAtDesc`
- `ChargingSessionService` — `startSession`: validates ownership, checks EXPIRED specifically, checks point is RESERVED, calls `reservation.markUsed()` + `point.startCharging()`, persists session; `stopSession`: simulates energy (5–60 kWh), calls `session.complete()` + `point.stopCharging()`; `listSessions`, `getSession` with ownership checks
- `ChargingSessionController` — `POST /api/charging-sessions` (201), `POST /api/charging-sessions/{id}/stop` (200), `GET /api/charging-sessions` (200), `GET /api/charging-sessions/{id}` (200)
- `ChargingSessionMapper` — entity → `ChargingSessionResponse` record
- `api/StartChargingSessionRequest`, `api/ChargingSessionResponse` — Java records
- `session/exception/` — `ChargingSessionNotFoundException` (404), `ChargingSessionAccessDeniedException` (403), `InvalidChargingSessionStatusTransitionException` (409)
- `reservation/exception/ReservationExpiredException` (409, `RESERVATION_EXPIRED`)
- `GlobalExceptionHandler` — handlers for all new session exceptions + `DataIntegrityViolationException` → 409 `CHARGING_POINT_NOT_AVAILABLE` (defense-in-depth for partial unique index race)
- 58 new tests: 13 domain (ChargingSession, ChargingPoint +6, ChargingReservation +3), 15 service unit, 13 `@WebMvcTest` controller, 8 integration (Testcontainers full flow: start/stop/history/access-denied)
- Total: 168 tests, all passing with Docker

### Added — CF-011 | DONE | Implemented Reservation Expiration (2026-10-06)

- `ChargingReservation.expire()` — CREATED/CONFIRMED → EXPIRED; throws `InvalidReservationStatusTransitionException` for terminal states (same guard pattern as `cancel()`)
- `ChargingReservationRepository.findActiveExpiredBefore(Instant, List<ReservationStatus>)` — `@Query` with `JOIN FETCH r.chargingPoint` to load the associated point in one SQL query (avoids N+1 during batch expiration)
- `ReservationExpirationJob` — `@Component` with `@Scheduled(fixedDelayString = "${chargeflow.reservation.expiration.poll-interval-ms:60000}")`; package-visible `expireReservationsBefore(Instant)` to allow time injection in tests; each run is one `@Transactional` batch
- `@EnableScheduling` added to `ChargingSessionServiceApplication`
- 9 new tests: 4 domain (`expire()` happy-path + invalid transitions), 5 unit (`ReservationExpirationJobTest` with Mockito), plus 6 integration tests in `ReservationExpirationIntegrationTest` (Testcontainers + JPQL update to set `validUntil` in the past)
- Total: 83 non-integration tests, all passing

### Added — CF-010 | DONE | Implemented Charging Reservations (2026-10-06)

- `db/migration/V3__create_reservation.sql` — `ALTER TABLE charging_point ADD COLUMN version BIGINT NOT NULL DEFAULT 0`; `charging_reservation` table (UUID PK, FK to `charging_point`, status CHECK, timestamps, optimistic lock `version`); partial unique index `WHERE status IN ('CREATED','CONFIRMED')` for DB-level defense against duplicate active reservations per point
- `ChargingPoint` — added `@Version Long version` for optimistic locking; `reserve()` (AVAILABLE→RESERVED, throws `ChargingPointNotAvailableException`), `releaseReservation()` (RESERVED→AVAILABLE, throws `InvalidChargingPointStatusTransitionException` otherwise)
- `ChargingPointNotAvailableException` (station.exception) — `CHARGING_POINT_NOT_AVAILABLE`, HTTP 409
- `ReservationStatus` — enum: CREATED, CONFIRMED, USED, CANCELLED, EXPIRED
- `ChargingReservation` — JPA entity; constructor sets CREATED status + 15-min `validUntil` passed by caller; `cancel()` (active states only); `isActive()` (CREATED or CONFIRMED)
- `ChargingReservationRepository` — Spring Data JPA; `findByCustomerIdOrderByReservedAtDesc`
- `ReservationService` — `createReservation`: loads point, calls `reserve()`, persists, returns response; `listReservations`, `getReservation` (access-check); `cancelReservation`: access-check, `cancel()`, `releaseReservation()` — all via Hibernate dirty-checking, no explicit save needed for updates
- `ReservationController` — `POST /api/reservations` (201), `GET /api/reservations` (200), `GET /api/reservations/{id}` (200), `DELETE /api/reservations/{id}` (204); `X-Customer-Id` header as JWT placeholder until CF-022
- `reservation/exception/` — `ReservationNotFoundException` (404, `RESERVATION_NOT_FOUND`), `ReservationAccessDeniedException` (403, `RESERVATION_ACCESS_DENIED`), `InvalidReservationStatusTransitionException` (409, `INVALID_RESERVATION_STATUS_TRANSITION`)
- `GlobalExceptionHandler` — handlers for all new exceptions + `OptimisticLockingFailureException` → 409 `CHARGING_POINT_NOT_AVAILABLE`
- `ReservationMapper` — entity → `ReservationResponse` record
- `api/CreateReservationRequest`, `api/ReservationResponse` — Java records
- 33 new tests: 8 domain, 13 service unit, 12 `@WebMvcTest` controller, plus 10 integration tests (Testcontainers) including concurrent reservation race (optimistic locking)
- Total: 78 tests; 74 pass without Docker; all 78 pass with Docker running

### Added — CF-009 | DONE | Implemented charging point operational availability rules and operator status API (2026-09-14)

- `ChargingPoint` — added domain methods: `isAvailableForReservation()` (`active && status == AVAILABLE`), `markOutOfService()` (only from AVAILABLE), `restoreToAvailable()` (only from OUT_OF_SERVICE); invalid transitions throw `InvalidChargingPointStatusTransitionException`; no public status setter exposed
- `exception/ChargingPointNotFoundException` — `CHARGING_POINT_NOT_FOUND`, HTTP 404
- `exception/InvalidChargingPointStatusTransitionException` — `INVALID_CHARGING_POINT_STATUS_TRANSITION`, HTTP 409; carries currentStatus and requestedStatus
- `api/ChangeChargingPointStatusRequest` — record with `@NotNull ChargingPointStatus status`; invalid enum → 400, null → 400
- `ChargingPointAdminService` — `changeOperationalStatus(UUID, ChargingPointStatus)`: loads point, delegates to domain method, persists via Hibernate dirty-checking, returns `ChargingPointResponse`; `@Transactional`
- `ChargingPointAdminController` — `PATCH /api/admin/charging-points/{chargingPointId}/status`; auth deferred to CF-022
- `GlobalExceptionHandler` — added handlers for `ChargingPointNotFoundException` (404), `InvalidChargingPointStatusTransitionException` (409), `HttpMessageNotReadableException` (400), `MethodArgumentNotValidException` (400)
- `StationMapper` — uses `ChargingPoint::isAvailableForReservation` for available-count (canonical rule, no behavior change)
- No Flyway migration required (schema unchanged)
- 28 new tests: 13 domain, 7 service, 4 MVC (`@WebMvcTest`), 4 integration (Testcontainers PostgreSQL)
- Total: 45 tests, all passing

### Changed — CF-008 cleanup — Corrected UUID strategy and MVC test approach (2026-09-14)

- `ChargingStation` — removed `this.id = UUID.randomUUID()` from constructor; Hibernate owns UUID via `@GeneratedValue(GenerationType.UUID)` exclusively
- `ChargingStationServiceTest` — added `persistedStation()` spy helper: `spy(station)` + `doReturn(UUID.randomUUID()).when(spy).getId()` simulates Hibernate-set ID without polluting the entity model
- `ChargingStationControllerTest` — replaced `MockMvcBuilders.standaloneSetup()` with `@WebMvcTest(controllers = …, excludeAutoConfiguration = {OAuth2ResourceServerAutoConfiguration.class, OAuth2ResourceServerWebSecurityAutoConfiguration.class})`; `GlobalExceptionHandler` auto-included by `@WebMvcTest`; OAuth2 auto-configs excluded because `HttpSecurity` is not a Spring bean in the `@WebMvcTest` slice (Spring Boot 4.x/Spring Security 7.x), and JWT is deferred to CF-022

### Added — CF-008 — Implemented Station and Charging Point Catalog (2026-09-14)

- `db/migration/V1__create_station_catalog.sql` — `charging_station` + `charging_point` tables with FK, CHECK, UNIQUE constraints and partial/covering indexes
- `db/migration/V2__seed_demo_station_catalog.sql` — 3 Lublin demo stations, 7 charging points (CCS + TYPE_2), all AVAILABLE, fixed UUIDs
- `ChargingStation`, `ChargingPoint` — JPA entities; BigDecimal for maxPowerKw, pricePerKwh
- `ConnectorType` (CCS, TYPE_2), `ChargingPointStatus` (AVAILABLE, RESERVED, CHARGING, OUT_OF_SERVICE) — string-mapped enums
- `ChargingStationRepository`, `ChargingPointRepository` — Spring Data JPA; `findActiveByStationIds` batch-loads all points in one query to avoid N+1
- `ChargingStationService` — 2 queries for list (stations + batch points), 2 for detail; groups by stationId in Java
- `ChargingStationController` — `GET /api/stations`, `GET /api/stations/{stationId}`
- `ChargingStationSummaryResponse`, `ChargingStationDetailsResponse`, `ChargingPointResponse` — Java records
- `ChargingStationNotFoundException` + `GlobalExceptionHandler` + `ApiError` record — 404 with `CHARGING_STATION_NOT_FOUND` error code
- `StationMapper` — maps entity → response, counts AVAILABLE points for summary
- `pom.xml` — replaced `flyway-core` with `spring-boot-starter-flyway` (autoconfiguration split out in Spring Boot 4.x); added `flyway-database-postgresql`; added `spring-boot-starter-webmvc-test` (test scope)
- `application.yml` — `spring.jackson.datatype.datetime.write-dates-as-timestamps: false` (Jackson 3.x replaces `serialization.write-dates-as-timestamps`)
- 17 tests: 2 app smoke (Testcontainers), 7 Flyway+repo integration (Testcontainers), 5 service unit, 3 `@WebMvcTest` controller

### Added — CF-007 — Defined ChargeFlow V1 business domain, workflows and API requirements (2026-09-14)

- `docs/product-specs/chargeflow-spec.md` — full V1 business specification: actors, domain model, state transitions, REST APIs, domain events, Transactional Outbox, notification flow, error model, concurrency scenario, frontend scope, V1 exclusions, V2 boundary, later roadmap
- `docs/exec-plans/backlog.md` — extended with CF-007 through CF-030 (24 total tasks)
- `charging-session-service/CLAUDE.md` — corrected Java version from 21 to 25

### Added — CF-006 — Initialized Terraform infrastructure foundation (2026-09-14)

- `infrastructure/environments/local/` — Terraform root for LocalStack; uses `AWS_ENDPOINT_URL`, `skip_credentials_validation`, `s3_use_path_style`
- `infrastructure/environments/dev/` — Terraform root for real AWS dev account; credentials from standard AWS mechanisms
- `infrastructure/environments/prod/` — Terraform root for real AWS prod account; identical provider strategy to dev
- All environments: `versions.tf` (Terraform ~> 1.16.2, AWS Provider ~> 6.64.0), `providers.tf` (provider + common_tags locals), `variables.tf` (environment, aws_region, project_name with validation), `outputs.tf` (empty placeholder), `terraform.tfvars.example`
- `infrastructure/modules/` — placeholder directories for 14 planned modules: network, ecr, ecs, alb, rds, messaging, dynamodb, cognito, frontend, lambda, api-gateway, monitoring, kms, route53
- `infrastructure/README.md` — full infrastructure documentation
- `.gitignore` — fixed: `.terraform.lock.hcl` is no longer ignored (lock files are committed); added `crash.log`/`crash.*.log`
- `README.md` — updated project status table

### Added — CF-005 — Local Docker Compose foundation (2026-09-14)

- `docker-compose.yml` — PostgreSQL 18.6 + LocalStack 4.14.0; no application containers; no AWS resource provisioning
- `.env.example` — documented local development environment variables; `.env` excluded from version control
- `charging-session-service/mvnw` — fixed to auto-detect and use Java 25 when system JAVA_HOME points elsewhere
- `notification-service/mvnw` — same Java 25 fix
- `README.md` — Local Development section expanded with full setup instructions

### Added — CF-004 — Initialized Angular frontend (2026-09-14)

- `frontend/angular.json` — Angular 22.1.0 workspace config, standalone, routing, SCSS, strict, no SSR
- `frontend/package.json` — Angular 22.1.x dependencies, Vitest test runner, TypeScript 6.0.x
- `frontend/.nvmrc` — Node 24 LTS pinned
- `frontend/src/app/app.ts` — root standalone component
- `frontend/src/app/app.html` — minimal ChargeFlow shell (heading, subtitle, V1 status)
- `frontend/src/app/app.spec.ts` — tests: app creates + h1 contains "ChargeFlow"
- `frontend/src/app/app.config.ts` — provideRouter bootstrapped
- `frontend/src/app/app.routes.ts` — empty routes for future feature areas
- `frontend/CLAUDE.md` — preserved (not modified)

### Added — CF-003 — Initialized Notification Service (2026-09-14)

- `notification-service/pom.xml` — Spring Boot 4.1.1, Java 25; no JPA/PostgreSQL/Flyway
- `NotificationServiceApplication.java` — main entry point, package `pl.chargeflow.notification`
- `SecurityConfig.java` — permitAll for `/actuator/health`, denyAll for all other requests
- `application.yml` — app name, Actuator health exposed; no DB or AWS config
- `Dockerfile` — multi-stage build: JDK 25 build, JRE 25 runtime, non-root `chargeflow` user
- `mvnw` / `mvnw.cmd` — Maven wrapper scripts
- `NotificationServiceApplicationTests.java` — context load + health endpoint test (no external infrastructure required)

### Added — CF-002 — Initialized Charging Session Service (2026-09-14)

- `charging-session-service/pom.xml` — Spring Boot 4.1.1, Java 25, Maven project
- `ChargingSessionServiceApplication.java` — main entry point, package `pl.chargeflow.session`
- `SecurityConfig.java` — permits `/actuator/health`, denyAll for all other requests (JWT/Cognito will be configured in a dedicated security task)
- `application.yml` — datasource via env vars, Flyway enabled, Actuator health exposed
- `db/migration/` — Flyway migration directory (no domain migrations yet)
- `Dockerfile` — multi-stage build: JDK 25 build, JRE 25 runtime, non-root `chargeflow` user
- `mvnw` / `mvnw.cmd` — Maven wrapper scripts
- `ChargingSessionServiceApplicationTests.java` — context load + health endpoint test using Testcontainers PostgreSQL 2.0.5

### Added — CF-001 — Repository structure (2026-09-14)

- Root `CLAUDE.md` with project rules and V1 scope
- `ARCHITECTURE.md` — V1 high-level architecture diagram and deployment model
- `README.md` — professional project overview
- `CHANGELOG.md` — this file
- `.gitignore` — covers IntelliJ, VS Code, Maven, Node, Angular, Terraform, secrets, OS files
- `docker-compose.yml` — minimal placeholder for local infrastructure
- `docs/product-specs/chargeflow-spec.md` — business spec placeholder (DRAFT)
- `docs/architecture/` — placeholder directory for architecture docs
- `docs/contracts/events/` and `docs/contracts/rest/` — API contract directories
- `docs/exec-plans/backlog.md` — initial task backlog (CF-001 through CF-006)
- `docs/decisions/` — ADR directory
- `charging-session-service/CLAUDE.md` — service rules and package structure
- `notification-service/CLAUDE.md` — service rules and AWS service list
- `frontend/CLAUDE.md` — Angular frontend rules
- `infrastructure/CLAUDE.md` — Terraform rules, environment structure, planned AWS services
- `integration-tests/README.md` — placeholder
- `.github/workflows/` — CI/CD directory placeholder
- `.claude/settings.json` — conservative project permissions
