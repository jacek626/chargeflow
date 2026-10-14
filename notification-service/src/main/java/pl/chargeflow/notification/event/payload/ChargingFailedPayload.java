package pl.chargeflow.notification.event.payload;

import java.util.UUID;

public record ChargingFailedPayload(
        String customerId,
        UUID chargingPointId
) {}
