package pl.chargeflow.session.event.payload;

import java.math.BigDecimal;
import java.util.UUID;

public record ChargingCompletedPayload(
        String customerId,
        String customerEmail,
        UUID chargingPointId,
        BigDecimal energyConsumedKwh,
        BigDecimal totalCost
) {}
