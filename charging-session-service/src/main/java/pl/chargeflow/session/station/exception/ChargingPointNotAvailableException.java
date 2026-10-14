package pl.chargeflow.session.station.exception;

import java.util.UUID;

public class ChargingPointNotAvailableException extends RuntimeException {

    private static final String ERROR_CODE = "CHARGING_POINT_NOT_AVAILABLE";

    public ChargingPointNotAvailableException(UUID chargingPointId) {
        super("Charging point is not available for reservation.");
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
