# Frontend — CLAUDE.md

Technology: **Angular**

## Responsibilities

- Authentication UI
- Charger/station browsing
- Reservation UI
- Charging session UI
- Session history
- Notifications
- Operator/admin screens

## Frontend must NOT be the source of truth for

- Authorization
- Pricing
- Reservation rules
- Status transitions
- Payment rules

Business rules must be enforced by backend services.
