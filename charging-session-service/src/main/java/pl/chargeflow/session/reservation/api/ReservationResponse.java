package pl.chargeflow.session.reservation.api;

import pl.chargeflow.session.reservation.ReservationStatus;
import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
        UUID id,
        String customerId,
        UUID chargingPointId,
        String chargingPointCode,
        String stationName,
        ReservationStatus status,
        Instant reservedAt,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {}
