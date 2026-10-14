package pl.chargeflow.session.station.api;

import jakarta.validation.constraints.NotNull;
import pl.chargeflow.session.station.ChargingPointStatus;

public record ChangeChargingPointStatusRequest(
        @NotNull ChargingPointStatus status
) {}
