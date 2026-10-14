package pl.chargeflow.session.station.exception;

import java.util.UUID;

public class ChargingPointNotFoundException extends RuntimeException {

    private static final String ERROR_CODE = "CHARGING_POINT_NOT_FOUND";

    public ChargingPointNotFoundException(UUID chargingPointId) {
        super("Charging point was not found.");
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
