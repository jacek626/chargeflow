# Domain Event Contracts — ChargeFlow V1

All events are produced by **Charging Session Service** and consumed by **Notification Service**,
except `NOTIFICATION_SENT` and `NOTIFICATION_FAILED` which are produced by Notification Service.

Transport: SNS → SQS (V1). Events are emitted via the Transactional Outbox (CF-015).

---

## Event envelope (all events)

Every message published to SNS has this top-level structure:

```json
{
  "eventId":      "uuid",
  "eventType":    "RESERVATION_CREATED",
  "eventVersion": "1.0",
  "occurredAt":   "2026-09-14T17:00:00Z",
  "aggregateId":  "uuid",
  "payload":      { ... }
}
```

| Field          | Type    | Description                                          |
|----------------|---------|------------------------------------------------------|
| `eventId`      | UUID    | Unique identifier — used for idempotency in consumer |
| `eventType`    | String  | See event types below                                |
| `eventVersion` | String  | Contract version, e.g. `"1.0"`                       |
| `occurredAt`   | Instant | ISO-8601 UTC timestamp of when the event occurred    |
| `aggregateId`  | UUID    | ID of the primary aggregate (reservation or session) |

---

## Events produced by Charging Session Service

### RESERVATION_CREATED

Published when a customer successfully creates a reservation.

`aggregateId` = `reservationId`

```json
{
  "eventId":      "uuid",
  "eventType":    "RESERVATION_CREATED",
  "eventVersion": "1.0",
  "occurredAt":   "2026-09-14T17:00:00Z",
  "aggregateId":  "uuid",
  "payload": {
    "customerId":       "cognito-sub-uuid",
    "chargingPointId":  "uuid",
    "validUntil":       "2026-09-14T17:15:00Z"
  }
}
```

| Payload field     | Type    | Description                        |
|-------------------|---------|------------------------------------|
| `customerId`      | String  | Customer identity from JWT         |
| `chargingPointId` | UUID    | Reserved charging point            |
| `validUntil`      | Instant | Reservation expiry (reservedAt +15 min) |

---

### RESERVATION_CANCELLED

Published when a customer cancels their own reservation.

`aggregateId` = `reservationId`

```json
{
  "eventId":      "uuid",
  "eventType":    "RESERVATION_CANCELLED",
  "eventVersion": "1.0",
  "occurredAt":   "2026-09-14T17:05:00Z",
  "aggregateId":  "uuid",
  "payload": {
    "customerId":       "cognito-sub-uuid",
    "chargingPointId":  "uuid"
  }
}
```

| Payload field     | Type   | Description          |
|-------------------|--------|----------------------|
| `customerId`      | String | Customer identity    |
| `chargingPointId` | UUID   | Released point       |

---

### RESERVATION_EXPIRED

Published by the expiration job when a reservation passes its `validUntil` without being used.

`aggregateId` = `reservationId`

```json
{
  "eventId":      "uuid",
  "eventType":    "RESERVATION_EXPIRED",
  "eventVersion": "1.0",
  "occurredAt":   "2026-09-14T17:15:00Z",
  "aggregateId":  "uuid",
  "payload": {
    "customerId":       "cognito-sub-uuid",
    "chargingPointId":  "uuid"
  }
}
```

Same payload shape as `RESERVATION_CANCELLED`.

---

### CHARGING_STARTED

Published when a customer starts a charging session.

`aggregateId` = `sessionId`

```json
{
  "eventId":      "uuid",
  "eventType":    "CHARGING_STARTED",
  "eventVersion": "1.0",
  "occurredAt":   "2026-09-14T17:03:00Z",
  "aggregateId":  "uuid",
  "payload": {
    "customerId":       "cognito-sub-uuid",
    "chargingPointId":  "uuid",
    "reservationId":    "uuid"
  }
}
```

| Payload field     | Type   | Description                      |
|-------------------|--------|----------------------------------|
| `customerId`      | String | Customer identity                |
| `chargingPointId` | UUID   | Charging point in use            |
| `reservationId`   | UUID   | Reservation used to start session |

---

### CHARGING_COMPLETED

Published when a customer stops a session and the cost is calculated.

`aggregateId` = `sessionId`

```json
{
  "eventId":      "uuid",
  "eventType":    "CHARGING_COMPLETED",
  "eventVersion": "1.0",
  "occurredAt":   "2026-09-14T18:10:00Z",
  "aggregateId":  "uuid",
  "payload": {
    "customerId":          "cognito-sub-uuid",
    "chargingPointId":     "uuid",
    "energyConsumedKwh":   27.4500,
    "totalCost":           68.34
  }
}
```

| Payload field        | Type           | Description                              |
|----------------------|----------------|------------------------------------------|
| `customerId`         | String         | Customer identity                        |
| `chargingPointId`    | UUID           | Charging point used                      |
| `energyConsumedKwh`  | Decimal (4dp)  | Energy consumed                          |
| `totalCost`          | Decimal (2dp)  | Calculated cost in PLN                   |

---

### CHARGING_FAILED

Published when a session ends abnormally (e.g. system error).

`aggregateId` = `sessionId`

```json
{
  "eventId":      "uuid",
  "eventType":    "CHARGING_FAILED",
  "eventVersion": "1.0",
  "occurredAt":   "2026-09-14T18:10:00Z",
  "aggregateId":  "uuid",
  "payload": {
    "customerId":       "cognito-sub-uuid",
    "chargingPointId":  "uuid"
  }
}
```

Same payload shape as `RESERVATION_CANCELLED`.

---

## Events produced by Notification Service

### NOTIFICATION_SENT

Published after a notification email is successfully sent via SES.

`aggregateId` = `notificationId`

```json
{
  "eventId":      "uuid",
  "eventType":    "NOTIFICATION_SENT",
  "eventVersion": "1.0",
  "occurredAt":   "2026-09-14T17:00:05Z",
  "aggregateId":  "uuid",
  "payload": {
    "customerId":    "cognito-sub-uuid",
    "triggerEventType": "RESERVATION_CREATED"
  }
}
```

| Payload field       | Type   | Description                           |
|---------------------|--------|---------------------------------------|
| `customerId`        | String | Recipient customer identity           |
| `triggerEventType`  | String | The domain event that triggered this  |

---

### NOTIFICATION_FAILED

Published when a notification attempt fails after all retries.

`aggregateId` = `notificationId`

```json
{
  "eventId":      "uuid",
  "eventType":    "NOTIFICATION_FAILED",
  "eventVersion": "1.0",
  "occurredAt":   "2026-09-14T17:00:10Z",
  "aggregateId":  "uuid",
  "payload": {
    "customerId":        "cognito-sub-uuid",
    "triggerEventType":  "RESERVATION_CREATED",
    "reason":            "SES_BOUNCE"
  }
}
```

| Payload field       | Type   | Description                                    |
|---------------------|--------|------------------------------------------------|
| `customerId`        | String | Recipient customer identity                    |
| `triggerEventType`  | String | The domain event that triggered this           |
| `reason`            | String | Short failure reason (e.g. `SES_BOUNCE`)       |

---

## Notification triggers (V1)

| Event received          | Email sent to customer                              |
|-------------------------|-----------------------------------------------------|
| RESERVATION_CREATED     | Reservation confirmation with point and expiry time |
| RESERVATION_CANCELLED   | Cancellation confirmation                           |
| RESERVATION_EXPIRED     | Expiry notification                                 |
| CHARGING_STARTED        | Charging started confirmation                       |
| CHARGING_COMPLETED      | Session summary: energy consumed and total cost     |

`CHARGING_FAILED` — no email in V1 (no customer-facing failure message defined).
