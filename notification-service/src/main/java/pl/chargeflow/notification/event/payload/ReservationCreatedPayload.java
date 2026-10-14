package pl.chargeflow.notification.event.payload;

import java.time.Instant;
import java.util.UUID;

public record ReservationCreatedPayload(
        String customerId,
        String customerEmail,
        UUID chargingPointId,
        Instant validUntil
) {}
