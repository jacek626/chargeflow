package pl.chargeflow.session.station.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ChargingStationDetailsResponse(
        UUID id,
        String name,
        String address,
        String city,
        BigDecimal latitude,
        BigDecimal longitude,
        boolean active,
        List<ChargingPointResponse> chargingPoints
) {}
