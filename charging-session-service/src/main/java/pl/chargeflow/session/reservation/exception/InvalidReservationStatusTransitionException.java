package pl.chargeflow.session.reservation.exception;

import pl.chargeflow.session.reservation.ReservationStatus;
import java.util.UUID;

public class InvalidReservationStatusTransitionException extends RuntimeException {

    private static final String ERROR_CODE = "INVALID_RESERVATION_STATUS_TRANSITION";
    private final ReservationStatus currentStatus;
    private final ReservationStatus requestedStatus;

    public InvalidReservationStatusTransitionException(UUID reservationId,
                                                        ReservationStatus currentStatus,
                                                        ReservationStatus requestedStatus) {
        super("Cannot transition reservation from " + currentStatus + " to " + requestedStatus + ".");
        this.currentStatus = currentStatus;
        this.requestedStatus = requestedStatus;
    }

    public String getErrorCode() { return ERROR_CODE; }
    public ReservationStatus getCurrentStatus() { return currentStatus; }
    public ReservationStatus getRequestedStatus() { return requestedStatus; }
}
