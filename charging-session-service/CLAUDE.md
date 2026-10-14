# Charging Session Service — CLAUDE.md

Root package: `pl.chargeflow.session`

## Responsibilities in V1

- Charging station catalog/projection needed by V1
- Charger information needed by V1
- Charging reservations
- Charging sessions
- Reservation/session status history
- Transactional outbox
- Tracking/status projection

## Does NOT own

- Notifications
- Payments
- Advanced station availability management

## Technology rules

- Java 25
- Spring Boot
- Maven
- PostgreSQL
- Spring Data JPA
- Flyway
- Spring Security
- OAuth2 Resource Server
- Testcontainers
- Constructor injection — no field injection
- Package-by-feature
- Use `BigDecimal` for monetary values — never `double` for money
- Database changes require Flyway migrations
- Domain transactions must not publish SNS events directly
- Use Transactional Outbox when messaging is implemented

## Package structure

Prefer package-by-feature:

```
pl.chargeflow.session
├── charger
├── reservation
├── session
├── outbox
├── tracking
├── security
├── config
└── common
```

Do NOT create a global application-wide structure based only on:

```
controller/
service/
repository/
dto/
entity/
```
