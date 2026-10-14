package pl.chargeflow.session.session.exception;

import pl.chargeflow.session.session.SessionStatus;
import java.util.UUID;

public class InvalidChargingSessionStatusTransitionException extends RuntimeException {

    private static final String ERROR_CODE = "INVALID_CHARGING_SESSION_STATUS_TRANSITION";
    private final SessionStatus currentStatus;
    private final SessionStatus requestedStatus;

    public InvalidChargingSessionStatusTransitionException(UUID sessionId,
                                                           SessionStatus currentStatus,
                                                           SessionStatus requestedStatus) {
        super("Cannot transition charging session from " + currentStatus + " to " + requestedStatus + ".");
        this.currentStatus = currentStatus;
        this.requestedStatus = requestedStatus;
    }

    public String getErrorCode() { return ERROR_CODE; }
    public SessionStatus getCurrentStatus() { return currentStatus; }
    public SessionStatus getRequestedStatus() { return requestedStatus; }
}
