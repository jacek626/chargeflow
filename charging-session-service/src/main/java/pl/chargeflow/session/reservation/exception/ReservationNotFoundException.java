package pl.chargeflow.session.reservation.exception;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {

    private static final String ERROR_CODE = "RESERVATION_NOT_FOUND";

    public ReservationNotFoundException(UUID reservationId) {
        super("Reservation was not found.");
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
