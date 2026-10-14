# Notification Service — CLAUDE.md

Root package: `pl.chargeflow.notification`

## Responsibilities

- Consume domain events
- Create notifications
- Store notification history
- Send emails
- Publish notification result events

## Storage

- DynamoDB

## Do NOT introduce

- JPA
- PostgreSQL
- Flyway

## AWS services planned for this service

- SQS
- SNS
- DynamoDB
- SES

## Reliability rules

- Consumers must be idempotent
- Duplicate `eventId` must not trigger duplicate emails
- Failed messages must support retry and DLQ
