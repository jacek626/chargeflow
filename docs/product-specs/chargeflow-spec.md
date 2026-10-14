# ChargeFlow Business Specification — V1

Status: **ACTIVE**

This document is the business source of truth for ChargeFlow V1.
Future implementation tasks must not contradict it without an explicit update.

---

## 1. Product definition

**ChargeFlow** is an Event-Driven EV Charging Platform on AWS.

V1 is a portfolio and training application with realistic business flows.
It is not intended to simulate a full commercial EV charging network.

Every implemented feature must either:
- provide real business value in the EV charging domain, or
- teach an important architectural, Java, or AWS concept.

Avoid unnecessary complexity.

---

## 2. Actors

V1 defines exactly two authenticated roles.

### CUSTOMER

| Capability | Description |
|---|---|
| Login | Authenticate via Cognito |
| Browse stations | View active charging stations |
| Inspect charging points | View availability and pricing |
| Create reservation | Reserve an available charging point |
| View reservations | List and view own reservations |
| Start charging | Start a session from a valid reservation |
| Stop charging | Stop own active charging session |
| View session history | List past charging sessions |
| View session details | View cost, energy, duration |

### OPERATOR

| Capability | Description |
|---|---|
| View stations | View all stations and charging points |
| View reservations | View all reservations |
| View charging sessions | View all charging sessions |
| Change point availability | Mark a charging point AVAILABLE or OUT_OF_SERVICE (where allowed) |

No additional roles exist in V1.

---

## 3. Service boundaries

### Charging Session Service

**Owns:**
- Station catalog (V1 scope only — no independent Station Service yet)
- Charging point catalog
- Charging reservations
- Charging sessions
- Reservation and session status history
- Pricing calculation
- Transactional Outbox

**Persistence:** PostgreSQL

### Notification Service

**Owns:**
- Notification processing
- Email notifications via SES
- Notification history
- Idempotent consumption of domain events

**Persistence:** DynamoDB

**Does NOT own:**
- Business logic for charging or reservations
- Charging point status management

### Explicitly excluded from V1

| Service | Target version |
|---|---|
| Station Service (independent) | V2 |
| Payment Service | V2 |
| Analytics Service | V3+ |

---

## 4. Domain model

### 4.1 ChargingStation

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| name | String | Display name |
| address | String | Street address |
| city | String | City |
| latitude | Decimal | Geographic coordinate |
| longitude | Decimal | Geographic coordinate |
| active | Boolean | Whether the station is visible and operational |

A station contains one or more ChargingPoints.

---

### 4.2 ChargingPoint

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| stationId | UUID | FK to ChargingStation |
| code | String | Human-readable identifier, e.g. LUB-C01 |
| connectorType | Enum | CCS or TYPE_2 |
| maxPowerKw | Decimal | Maximum charging power in kW |
| pricePerKwh | Decimal | Price per kWh in PLN (BigDecimal in Java) |
| status | Enum | Current operational status |
| active | Boolean | Whether the point accepts reservations |

**ConnectorType values:**

| Value | Description |
|---|---|
| CCS | Combined Charging System (DC fast charging) |
| TYPE_2 | IEC 62196 Type 2 (AC charging) |

**ChargingPointStatus values:**

| Status | Meaning |
|---|---|
| AVAILABLE | Ready for reservation |
| RESERVED | Reserved by a customer |
| CHARGING | Active charging session in progress |
| OUT_OF_SERVICE | Taken offline by operator |

**Availability rule:**

A charging point is available for reservation only when:

```
active == true AND status == AVAILABLE
```

This is the single authoritative rule used everywhere: reservation logic, station summary counts, and availability checks.

---

### 4.3 ChargingReservation

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| customerId | String | Identity from JWT (not from request body) |
| chargingPointId | UUID | FK to ChargingPoint |
| status | Enum | Current reservation status |
| reservedAt | Instant | When the reservation was created |
| validUntil | Instant | Expiration time (reservedAt + 15 minutes) |
| createdAt | Instant | Record creation time |
| updatedAt | Instant | Last update time |
| version | Long | Optimistic locking version |

**ReservationStatus values:**

| Status | Meaning |
|---|---|
| CREATED | Just created, not yet explicitly confirmed |
| CONFIRMED | Confirmed (may be used in later V1 flow) |
| USED | Used to start a charging session |
| CANCELLED | Cancelled by the customer |
| EXPIRED | Expired without being used |

**Business rules:**
- A customer may only reserve an AVAILABLE charging point.
- One charging point cannot have multiple active (CREATED or CONFIRMED) reservations simultaneously.
- A reservation is valid for 15 minutes from creation.
- An EXPIRED reservation cannot be used to start a charging session.
- A customer may cancel their own reservation before charging starts.
- Starting a charging session transitions the reservation to USED.
- Optimistic locking protects concurrent reservation attempts.

---

### 4.4 ChargingSession

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| customerId | String | Identity from JWT |
| chargingPointId | UUID | FK to ChargingPoint |
| reservationId | UUID | FK to ChargingReservation |
| status | Enum | Current session status |
| startedAt | Instant | When the session was started |
| stoppedAt | Instant | When the session was stopped (null while active) |
| energyConsumedKwh | Decimal | Energy consumed (may be simulated in V1) |
| pricePerKwh | Decimal | Snapshot of price at session start (BigDecimal in Java) |
| totalCost | Decimal | Calculated by the backend (BigDecimal in Java) |
| createdAt | Instant | Record creation time |
| updatedAt | Instant | Last update time |
| version | Long | Optimistic locking version |

**SessionStatus values:**

| Status | Meaning |
|---|---|
| STARTED | Session initiated |
| CHARGING | Actively charging |
| COMPLETED | Session stopped, cost calculated |
| FAILED | Session ended abnormally |

**Business rules:**
- V1 does not communicate with physical EV chargers (OCPP not implemented).
- Energy consumption may be simulated by the backend.
- `totalCost` is always calculated server-side: `totalCost = energyConsumedKwh × pricePerKwh`.
- The frontend must never supply or influence the final cost.
- `pricePerKwh` is a snapshot taken at session start. Later price changes do not affect the stored cost.
- Money must use `BigDecimal` in Java — never `double` or `float` for monetary values.

---

## 5. Pricing

V1 pricing rule:

```
totalCost = energyConsumedKwh × pricePerKwh
```

- `pricePerKwh` is captured from the ChargingPoint at the moment charging starts.
- Historical charging sessions retain their original price snapshot.
- Prices are expressed in PLN.
- Use monetary precision appropriate for PLN (2 decimal places in presentation; store with sufficient precision).

**Excluded from V1:**
- Dynamic tariffs
- Time-of-day pricing
- Subscriptions or membership discounts
- Taxes
- Roaming fees

---

## 6. State transitions

### ChargingPoint status transitions

```
AVAILABLE ──── (customer creates reservation) ──────────► RESERVED
RESERVED  ──── (customer starts charging)     ──────────► CHARGING
CHARGING  ──── (customer stops charging)      ──────────► AVAILABLE

RESERVED  ──── (customer cancels)             ──────────► AVAILABLE
RESERVED  ──── (reservation expires)          ──────────► AVAILABLE

AVAILABLE ──── (operator marks out of service) ─────────► OUT_OF_SERVICE
OUT_OF_SERVICE ── (operator marks available)  ──────────► AVAILABLE
```

**Reservation of a charging point is NOT allowed when status is:**
- RESERVED
- CHARGING
- OUT_OF_SERVICE

**An operator may NOT move a CHARGING or RESERVED point to any status.**
A point returns to AVAILABLE only after the charging session is completed or failed (CHARGING → AVAILABLE), or after the reservation is released (RESERVED → AVAILABLE).
Operator transitions are limited to: AVAILABLE ↔ OUT_OF_SERVICE.

### ReservationStatus transitions

```
CREATED ──── (charging started)   ──────────────────────► USED
CREATED ──── (customer cancels)   ──────────────────────► CANCELLED
CREATED ──── (expiration timer)   ──────────────────────► EXPIRED
```

### SessionStatus transitions

```
STARTED  ──── (charging progresses)  ─────────────────────► CHARGING
CHARGING ──── (customer stops)       ─────────────────────► COMPLETED
STARTED  ──── (error)                ─────────────────────► FAILED
CHARGING ──── (error)                ─────────────────────► FAILED
```

---

## 7. Reservation expiration

- A reservation is valid for **15 minutes** from the moment it is created.
- `validUntil = reservedAt + 15 minutes`
- After expiration:
  - `ReservationStatus` → EXPIRED
  - `ChargingPointStatus` → AVAILABLE
- Expiration processing implementation is not specified here. A scheduled mechanism (e.g. periodic polling or EventBridge) will be selected in the implementation task.

---

## 8. Simulated charging (V1)

V1 does not communicate with physical chargers. There is no OCPP integration.

Conceptual charging flow:

```
Reservation CONFIRMED / CREATED
        ↓  (customer starts)
ChargingPoint → CHARGING
ChargingSession → STARTED / CHARGING
        ↓  (simulated energy accumulation)
        ↓  (customer stops)
backend calculates totalCost
ChargingSession → COMPLETED
ChargingPoint → AVAILABLE
```

- For initial V1 implementation, stopping a session may accept or generate a simulated energy figure.
- The exact simulation mechanism is decided in the implementation task (CF-013).
- The backend is the sole authority over the final cost.

---

## 9. Development seed data

The following demo stations are used during local development. Data is deterministic and fictional.

### Station 1 — Lublin Centrum

| Code | Connector | Max Power | Price |
|---|---|---|---|
| LUB-C01 | CCS | 150 kW | 2.49 PLN/kWh |
| LUB-C02 | CCS | 150 kW | 2.49 PLN/kWh |
| LUB-T01 | TYPE_2 | 22 kW | 1.79 PLN/kWh |

### Station 2 — Lublin Czechów

| Code | Connector | Max Power | Price |
|---|---|---|---|
| LUB-CZ01 | CCS | 100 kW | 2.29 PLN/kWh |
| LUB-CZ02 | TYPE_2 | 22 kW | 1.69 PLN/kWh |

### Station 3 — Lublin Felin

| Code | Connector | Max Power | Price |
|---|---|---|---|
| LUB-F01 | CCS | 200 kW | 2.69 PLN/kWh |
| LUB-F02 | CCS | 200 kW | 2.69 PLN/kWh |

Addresses are fictional placeholders for development purposes.

---

## 10. Customer REST API

All customer endpoints require the CUSTOMER role.
Customer identity is extracted from the authenticated JWT — never from the request body.

### 10.1 Stations

| Method | Path | Description |
|---|---|---|
| GET | `/api/stations` | List active stations |
| GET | `/api/stations/{stationId}` | Get station details with charging points |

`GET /api/stations` — returns a list of active stations. Each item is a summary:

```json
[
  {
    "id": "uuid",
    "name": "Lublin Centrum",
    "city": "Lublin",
    "active": true,
    "chargingPointCount": 3,
    "availableChargingPointCount": 2
  }
]
```

`availableChargingPointCount` counts only points where `active = true AND status = AVAILABLE`.

`GET /api/stations/{stationId}` — returns full station data with all active charging points:

```json
{
  "id": "uuid",
  "name": "Lublin Centrum",
  "address": "ul. Krakowskie Przedmieście 1",
  "city": "Lublin",
  "latitude": 51.246454,
  "longitude": 22.561474,
  "active": true,
  "chargingPoints": [
    {
      "id": "uuid",
      "code": "LUB-C01",
      "connectorType": "CCS",
      "maxPowerKw": 150.00,
      "pricePerKwh": 2.49,
      "status": "AVAILABLE",
      "active": true
    }
  ]
}
```

Returns 404 with `CHARGING_STATION_NOT_FOUND` if the station does not exist or is not active.

---

### 10.2 Reservations

| Method | Path | Description |
|---|---|---|
| POST | `/api/reservations` | Create a reservation |
| GET | `/api/reservations` | List own reservations |
| GET | `/api/reservations/{reservationId}` | Get own reservation details |
| DELETE | `/api/reservations/{reservationId}` | Cancel own reservation |

**POST /api/reservations** request body:
```json
{
  "chargingPointId": "uuid"
}
```

- `customerId` is taken from the authenticated JWT — not accepted from the request body.
- Returns 409 if the charging point is not AVAILABLE.
- Returns 404 if the charging point does not exist.
- A customer may access only their own reservations (403 if otherwise).

**DELETE /api/reservations/{reservationId}:**
- Returns 409 if reservation is already USED, CANCELLED, or EXPIRED.

---

### 10.3 Charging Sessions

| Method | Path | Description |
|---|---|---|
| POST | `/api/charging-sessions` | Start a charging session |
| GET | `/api/charging-sessions` | List own charging sessions |
| GET | `/api/charging-sessions/{sessionId}` | Get own session details |
| POST | `/api/charging-sessions/{sessionId}/stop` | Stop own active session |

**POST /api/charging-sessions** request body:
```json
{
  "reservationId": "uuid"
}
```

- Returns 409 if reservation is EXPIRED, USED, or CANCELLED.
- Returns 409 if the charging point is not in RESERVED state.
- A customer may access only their own sessions (403 if otherwise).

**POST /api/charging-sessions/{sessionId}/stop:**
- Returns 409 if the session is already COMPLETED or FAILED.
- Backend calculates `energyConsumedKwh` and `totalCost`.

---

## 11. Operator REST API

All operator endpoints require the OPERATOR role.

| Method | Path | Description |
|---|---|---|
| GET | `/api/admin/stations` | List all stations |
| GET | `/api/admin/stations/{stationId}` | Get station with charging points |
| GET | `/api/admin/reservations` | List all reservations |
| GET | `/api/admin/charging-sessions` | List all charging sessions |
| PATCH | `/api/admin/charging-points/{chargingPointId}/status` | Change charging point operational status |

**PATCH /api/admin/charging-points/{chargingPointId}/status** request body:
```json
{
  "status": "OUT_OF_SERVICE"
}
```

Valid operator-initiated transitions:

| From | To | Allowed |
|---|---|---|
| AVAILABLE | OUT_OF_SERVICE | Yes |
| OUT_OF_SERVICE | AVAILABLE | Yes |
| RESERVED | OUT_OF_SERVICE | No |
| CHARGING | OUT_OF_SERVICE | No |
| CHARGING | AVAILABLE | No |

An operator may not interfere with an active reservation or charging session via this endpoint.

Returns 409 with `INVALID_CHARGING_POINT_STATUS_TRANSITION` for invalid transitions.
Returns 404 with `CHARGING_POINT_NOT_FOUND` if the charging point does not exist.
Returns 400 with `INVALID_REQUEST` if the status value is not a valid enum.

On success returns HTTP 200 with the updated charging point:

```json
{
  "id": "uuid",
  "code": "LUB-C01",
  "connectorType": "CCS",
  "maxPowerKw": 150.00,
  "pricePerKwh": 2.49,
  "status": "OUT_OF_SERVICE",
  "active": true
}
```

---

## 12. Domain events

All events are produced by Charging Session Service and consumed by Notification Service.

### 12.1 Event envelope (all events)

| Field | Type | Notes |
|---|---|---|
| eventId | UUID | Unique event identifier (used for idempotency) |
| eventType | String | Enum value below |
| eventVersion | String | e.g. "1.0" |
| occurredAt | Instant | When the business event occurred |
| aggregateId | UUID | ID of the primary aggregate |

### 12.2 V1 event types

| Event | Aggregate | Additional fields |
|---|---|---|
| RESERVATION_CREATED | reservationId | customerId, chargingPointId, validUntil |
| RESERVATION_CANCELLED | reservationId | customerId, chargingPointId |
| RESERVATION_EXPIRED | reservationId | customerId, chargingPointId |
| CHARGING_STARTED | sessionId | customerId, chargingPointId, reservationId |
| CHARGING_COMPLETED | sessionId | customerId, chargingPointId, energyConsumedKwh, totalCost |
| CHARGING_FAILED | sessionId | customerId, chargingPointId |
| NOTIFICATION_SENT | notificationId | customerId, eventType |
| NOTIFICATION_FAILED | notificationId | customerId, eventType, reason |

Event schemas will be maintained under `docs/contracts/events/`.

---

## 13. Transactional Outbox

Charging Session Service must use the Transactional Outbox pattern to publish domain events reliably.

A business state change and the corresponding outbox record must be persisted **atomically** in the same PostgreSQL transaction.

SNS messages must never be published directly inside a business transaction.

### OutboxEvent fields

| Field | Type | Notes |
|---|---|---|
| id | UUID | Primary key |
| aggregateId | UUID | ID of the business aggregate |
| aggregateType | String | e.g. RESERVATION, SESSION |
| eventType | String | Matches domain event type |
| payload | JSONB | Full event payload |
| createdAt | Instant | When the outbox record was created |
| publishedAt | Instant | When the record was successfully published (null until then) |
| status | Enum | NEW or PUBLISHED |

**OutboxEventStatus:**

| Status | Meaning |
|---|---|
| NEW | Created, not yet published to SNS |
| PUBLISHED | Successfully published to SNS |

Implementation is deferred to CF-015.

---

## 14. Notification flow

Target V1 messaging architecture:

```
Charging Session Service
        ↓ (atomic write)
PostgreSQL Outbox table
        ↓ (outbox poller)
SNS Topic
        ↓
SQS Queue (+ DLQ for failed messages)
        ↓
Notification Service consumer
        ↓ (idempotent using eventId)
DynamoDB (notification history)
        ↓
SES (email delivery)
        ↓
NOTIFICATION_SENT or NOTIFICATION_FAILED event
```

**Idempotency rule:** A duplicate message with the same `eventId` must not trigger a duplicate email.

SQS retries and DLQ configuration will be implemented during infrastructure provisioning.

---

## 15. Email notifications (V1 target)

| Trigger event | Email content |
|---|---|
| RESERVATION_CREATED | Reservation confirmation with station, point, and expiry time |
| RESERVATION_CANCELLED | Cancellation confirmation |
| RESERVATION_EXPIRED | Notification that the reservation has expired |
| CHARGING_STARTED | Charging started confirmation |
| CHARGING_COMPLETED | Session summary with energy consumed and total cost |

**Excluded from V1:**
- SMS notifications
- Push notifications
- In-app notifications

---

## 16. Authentication and authorization

**Authentication:** AWS Cognito (OAuth2 Authorization Code + PKCE for Angular, JWT validation in Spring).

**Backend:** Spring OAuth2 Resource Server with JWT validation.

**Angular:** Login and logout via Cognito. Bearer JWT sent with every API request.

### Authorization rules

| Endpoint set | Required role |
|---|---|
| `/api/stations/**` (read) | CUSTOMER or OPERATOR |
| `/api/reservations/**` | CUSTOMER |
| `/api/charging-sessions/**` | CUSTOMER |
| `/api/admin/**` | OPERATOR |

Additional rules:
- A customer may only access their own reservations and sessions.
- Accessing another customer's resource returns 403.
- The backend is the sole authorization source of truth.
- `customerId` is always taken from the JWT — never from the request body.

Authentication implementation is deferred to CF-022.

---

## 17. API error model

All API errors use a consistent structure:

```json
{
  "code": "CHARGING_POINT_NOT_AVAILABLE",
  "message": "Charging point LUB-C01 is not available for reservation.",
  "timestamp": "2026-09-14T17:00:00Z",
  "traceId": "abc123"
}
```

### Business error codes

| Code | HTTP | Scenario |
|---|---|---|
| CHARGING_STATION_NOT_FOUND | 404 | Station ID does not exist or is not active |
| CHARGING_POINT_NOT_FOUND | 404 | Point ID does not exist |
| CHARGING_POINT_NOT_AVAILABLE | 409 | Point is not AVAILABLE for reservation |
| INVALID_CHARGING_POINT_STATUS_TRANSITION | 409 | Operator attempted an invalid status change |
| RESERVATION_NOT_FOUND | 404 | Reservation ID does not exist |
| RESERVATION_EXPIRED | 409 | Reservation has expired |
| RESERVATION_ALREADY_USED | 409 | Reservation already used to start a session |
| RESERVATION_ACCESS_DENIED | 403 | Reservation belongs to another customer |
| INVALID_RESERVATION_STATUS_TRANSITION | 409 | Invalid reservation state for requested operation |
| CHARGING_SESSION_NOT_FOUND | 404 | Session ID does not exist |
| CHARGING_SESSION_ACCESS_DENIED | 403 | Session belongs to another customer |
| CHARGING_SESSION_ALREADY_COMPLETED | 409 | Session is already in a terminal state |
| INVALID_CHARGING_SESSION_STATUS_TRANSITION | 409 | Invalid session state for requested operation |
| INVALID_REQUEST | 400 | Request body is malformed or fails validation (e.g. unknown enum value, missing required field) |

HTTP status mapping:
- `404` — resource not found
- `403` — access denied
- `409` — business rule conflict or invalid state transition
- `400` — request validation failure or malformed body
- `500` — unexpected server error

---

## 18. Concurrency scenario

**Scenario:** Two customers attempt to reserve the same charging point at the same moment.

Expected outcome: exactly one reservation succeeds; the other receives a 409 CHARGING_POINT_NOT_AVAILABLE error.

Implementation must use database-level consistency. Optimistic or pessimistic locking will be evaluated during CF-010.

This scenario must have a dedicated integration test (covered in CF-026).

---

## 19. V1 frontend screens

### Customer screens

| Screen | Purpose |
|---|---|
| Login | Cognito login redirect |
| Stations | Map or list of active charging stations |
| Station details | Station info with charging points and their statuses |
| My Reservations | List of own reservations |
| Reservation details | Reservation status, point, expiry |
| Active Charging Session | Live session view with stop action |
| Charging History | List of past completed sessions |
| Charging Session Details | Energy consumed, cost, duration |

### Operator screens

| Screen | Purpose |
|---|---|
| Operator Dashboard | Overview of stations and utilization |
| Stations / Charging Points | Manage point availability |
| Reservations | View all reservations |
| Charging Sessions | View all sessions |

Implementation deferred to CF-023 (customer flow) and CF-024 (operator flow).

---

## 20. V1 exclusions

The following are explicitly **NOT** in V1:

| Item | Reason |
|---|---|
| Payment Service | V2 |
| Independent Station Service | V2 |
| Real payment processing | V2 |
| Kafka/MSK | Not required for V1 event volume |
| Physical charger / OCPP integration | Out of scope |
| Kubernetes / EKS | V3+ deployment option |
| Python Analytics Service | V3+ |
| Machine learning / scikit-learn | V3+ |
| Amazon Bedrock / RAG | V3+ |
| AI operations assistant | V3+ |
| Dynamic pricing / subscriptions | Not required in V1 |
| Roaming between charging providers | Not required in V1 |
| Mobile applications | Not required in V1 |
| SMS or push notifications | Not required in V1 |

---

## 21. V2 boundary

V2 introduces distributed payment and station management:

### Added services
- **Station / Availability Service** — independent service managing physical station state
- **Payment Service** — payment authorization and capture

### V2 reservation flow (Saga)

```
Create reservation
        ↓
Reserve charging point (Station Service)
        ↓
Authorize payment (Payment Service)
        ↓
Confirm reservation (Charging Session Service)
```

### V2 compensation flow (payment failure)

```
Payment authorization failed
        ↓
Release charging point reservation
        ↓
Cancel reservation
```

Notification Service remains outside the business Saga. Notification delivery failures must not affect charging or reservation transactions.

Compensating transactions and the Saga orchestration mechanism will be designed in V2 planning.

---

## 22. Later roadmap (post-V2)

| Phase | Technology / Feature |
|---|---|
| V3 — Deployment | Kubernetes / EKS deployment variant |
| V3+ — Telemetry | Kafka/MSK for real-time telemetry |
| V3+ — Analytics | Python Analytics Service with Pandas |
| V3+ — Metrics | Charging station utilization metrics |
| V3+ — Anomaly detection | scikit-learn anomaly detection on charging data |
| V3+ — GenAI | Amazon Bedrock, RAG knowledge base |
| V3+ — AI assistant | Read-only AI operations assistant |

No detailed design for post-V2 components in this document.
