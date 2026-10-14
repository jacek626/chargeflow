package pl.chargeflow.session.event.payload;

import java.util.UUID;

public record ChargingStartedPayload(
        String customerId,
        String customerEmail,
        UUID chargingPointId,
        UUID reservationId
) {}
