package pl.chargeflow.session.reservation.exception;

import java.util.UUID;

public class ReservationExpiredException extends RuntimeException {

    private static final String ERROR_CODE = "RESERVATION_EXPIRED";

    public ReservationExpiredException(UUID reservationId) {
        super("Reservation has expired.");
    }

    public String getErrorCode() { return ERROR_CODE; }
}
