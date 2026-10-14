package pl.chargeflow.session.station.api;

import pl.chargeflow.session.station.ChargingPointStatus;
import pl.chargeflow.session.station.ConnectorType;
import java.math.BigDecimal;
import java.util.UUID;

public record ChargingPointResponse(
        UUID id,
        String code,
        ConnectorType connectorType,
        BigDecimal maxPowerKw,
        BigDecimal pricePerKwh,
        ChargingPointStatus status,
        boolean active
) {}
