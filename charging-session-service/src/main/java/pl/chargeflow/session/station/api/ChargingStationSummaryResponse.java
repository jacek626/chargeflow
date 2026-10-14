package pl.chargeflow.session.station.api;

import java.util.UUID;

public record ChargingStationSummaryResponse(
        UUID id,
        String name,
        String city,
        boolean active,
        int chargingPointCount,
        int availableChargingPointCount
) {}
