package pl.chargeflow.session.reservation.exception;

import java.util.UUID;

public class ReservationAccessDeniedException extends RuntimeException {

    private static final String ERROR_CODE = "RESERVATION_ACCESS_DENIED";

    public ReservationAccessDeniedException(UUID reservationId) {
        super("Access to reservation is denied.");
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
