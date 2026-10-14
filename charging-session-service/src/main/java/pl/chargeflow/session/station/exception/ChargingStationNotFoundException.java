package pl.chargeflow.session.station.exception;

import java.util.UUID;

public class ChargingStationNotFoundException extends RuntimeException {

    private static final String ERROR_CODE = "CHARGING_STATION_NOT_FOUND";

    public ChargingStationNotFoundException(UUID stationId) {
        super("Charging station was not found.");
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
