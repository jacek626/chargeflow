package pl.chargeflow.session.session.exception;

import java.util.UUID;

public class ChargingSessionNotFoundException extends RuntimeException {

    private static final String ERROR_CODE = "CHARGING_SESSION_NOT_FOUND";

    public ChargingSessionNotFoundException(UUID sessionId) {
        super("Charging session was not found.");
    }

    public String getErrorCode() { return ERROR_CODE; }
}
