package pl.chargeflow.session.event.payload;

import java.util.UUID;

public record ChargingFailedPayload(
        String customerId,
        UUID chargingPointId
) {}
