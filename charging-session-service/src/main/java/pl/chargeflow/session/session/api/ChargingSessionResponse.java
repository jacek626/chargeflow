package pl.chargeflow.session.session.api;

import pl.chargeflow.session.session.SessionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ChargingSessionResponse(
        UUID id,
        String customerId,
        UUID chargingPointId,
        String chargingPointCode,
        String stationName,
        UUID reservationId,
        SessionStatus status,
        Instant startedAt,
        Instant stoppedAt,
        BigDecimal energyConsumedKwh,
        BigDecimal pricePerKwh,
        BigDecimal totalCost,
        Instant createdAt,
        Instant updatedAt
) {}
