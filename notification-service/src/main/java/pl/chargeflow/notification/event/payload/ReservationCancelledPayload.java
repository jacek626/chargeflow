package pl.chargeflow.notification.event.payload;

import java.util.UUID;

public record ReservationCancelledPayload(
        String customerId,
        String customerEmail,
        UUID chargingPointId
) {}
