package pl.chargeflow.session.station.exception;

import pl.chargeflow.session.station.ChargingPointStatus;
import java.util.UUID;

public class InvalidChargingPointStatusTransitionException extends RuntimeException {

    private static final String ERROR_CODE = "INVALID_CHARGING_POINT_STATUS_TRANSITION";
    private final ChargingPointStatus currentStatus;
    private final ChargingPointStatus requestedStatus;

    public InvalidChargingPointStatusTransitionException(UUID chargingPointId,
                                                         ChargingPointStatus currentStatus,
                                                         ChargingPointStatus requestedStatus) {
        super("Cannot transition charging point from " + currentStatus + " to " + requestedStatus + ".");
        this.currentStatus = currentStatus;
        this.requestedStatus = requestedStatus;
    }

    public String getErrorCode() { return ERROR_CODE; }
    public ChargingPointStatus getCurrentStatus() { return currentStatus; }
    public ChargingPointStatus getRequestedStatus() { return requestedStatus; }
}
