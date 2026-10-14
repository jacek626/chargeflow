package pl.chargeflow.session.session.exception;

import java.util.UUID;

public class ChargingSessionAccessDeniedException extends RuntimeException {

    private static final String ERROR_CODE = "CHARGING_SESSION_ACCESS_DENIED";

    public ChargingSessionAccessDeniedException(UUID sessionId) {
        super("Access to charging session is denied.");
    }

    public String getErrorCode() { return ERROR_CODE; }
}
