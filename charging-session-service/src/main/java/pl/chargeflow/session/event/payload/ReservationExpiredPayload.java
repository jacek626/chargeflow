package pl.chargeflow.session.event.payload;

import java.util.UUID;

public record ReservationExpiredPayload(
        String customerId,
        String customerEmail,
        UUID chargingPointId
) {}
