package pl.chargeflow.session.event.payload;

import java.util.UUID;

public record ReservationCancelledPayload(
        String customerId,
        String customerEmail,
        UUID chargingPointId
) {}
