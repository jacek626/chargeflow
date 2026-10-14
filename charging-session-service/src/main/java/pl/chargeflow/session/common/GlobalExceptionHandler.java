package pl.chargeflow.session.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pl.chargeflow.session.reservation.exception.InvalidReservationStatusTransitionException;
import pl.chargeflow.session.reservation.exception.ReservationAccessDeniedException;
import pl.chargeflow.session.reservation.exception.ReservationExpiredException;
import pl.chargeflow.session.reservation.exception.ReservationNotFoundException;
import pl.chargeflow.session.session.exception.ChargingSessionAccessDeniedException;
import pl.chargeflow.session.session.exception.ChargingSessionNotFoundException;
import pl.chargeflow.session.session.exception.InvalidChargingSessionStatusTransitionException;
import pl.chargeflow.session.station.exception.ChargingPointNotAvailableException;
import pl.chargeflow.session.station.exception.ChargingPointNotFoundException;
import pl.chargeflow.session.station.exception.ChargingStationNotFoundException;
import pl.chargeflow.session.station.exception.InvalidChargingPointStatusTransitionException;
import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ChargingStationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleStationNotFound(ChargingStationNotFoundException ex) {
        return new ApiError(ex.getErrorCode(), ex.getMessage(), Instant.now(), newTraceId());
    }

    @ExceptionHandler(ChargingPointNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleChargingPointNotFound(ChargingPointNotFoundException ex) {
        return new ApiError(ex.getErrorCode(), ex.getMessage(), Instant.now(), newTraceId());
    }

    @ExceptionHandler(InvalidChargingPointStatusTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleInvalidStatusTransition(InvalidChargingPointStatusTransitionException ex) {
        return new ApiError(ex.getErrorCode(), ex.getMessage(), Instant.now(), newTraceId());
    }

    @ExceptionHandler(ChargingPointNotAvailableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleChargingPointNotAvailable(ChargingPointNotAvailableException ex) {
        return new ApiError(ex.getErrorCode(), ex.getMessage(), Instant.now(), newTraceId());
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleReservationNotFound(ReservationNotFoundException ex) {
        return new ApiError(ex.getErrorCode(), ex.getMessage(), Instant.now(), newTraceId());
    }

    @ExceptionHandler(ReservationAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiError handleReservationAccessDenied(ReservationAccessDeniedException ex) {
        return new ApiError(ex.getErrorCode(), ex.getMessage(), Instant.now(), newTraceId());
    }

    @ExceptionHandler(InvalidReservationStatusTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleInvalidReservationTransition(InvalidReservationStatusTransitionException ex) {
        return new ApiError(ex.getErrorCode(), ex.getMessage(), Instant.now(), newTraceId());
    }

    // Concurrent reservation — optimistic lock on ChargingPoint version
    @ExceptionHandler(OptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
        return new ApiError("CHARGING_POINT_NOT_AVAILABLE",
                "Charging point is no longer available.", Instant.now(), newTraceId());
    }

    @ExceptionHandler(ReservationExpiredException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleReservationExpired(ReservationExpiredException ex) {
        return new ApiError(ex.getErrorCode(), ex.getMessage(), Instant.now(), newTraceId());
    }

    @ExceptionHandler(ChargingSessionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleChargingSessionNotFound(ChargingSessionNotFoundException ex) {
        return new ApiError(ex.getErrorCode(), ex.getMessage(), Instant.now(), newTraceId());
    }

    @ExceptionHandler(ChargingSessionAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiError handleChargingSessionAccessDenied(ChargingSessionAccessDeniedException ex) {
        return new ApiError(ex.getErrorCode(), ex.getMessage(), Instant.now(), newTraceId());
    }

    @ExceptionHandler(InvalidChargingSessionStatusTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleInvalidSessionTransition(InvalidChargingSessionStatusTransitionException ex) {
        return new ApiError(ex.getErrorCode(), ex.getMessage(), Instant.now(), newTraceId());
    }

    // Concurrent reservation — partial unique index on charging_reservation prevents two active
    // reservations for the same point (defense-in-depth, triggered when Hibernate INSERTs before UPDATEs)
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return new ApiError("CHARGING_POINT_NOT_AVAILABLE",
                "Charging point is no longer available.", Instant.now(), newTraceId());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleInvalidRequestBody(HttpMessageNotReadableException ex) {
        return new ApiError("INVALID_REQUEST", "Invalid request body.", Instant.now(), newTraceId());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleValidationFailure(MethodArgumentNotValidException ex) {
        return new ApiError("INVALID_REQUEST", "Request validation failed.", Instant.now(), newTraceId());
    }

    // Placeholder until CF-025 wires real distributed tracing.
    private String newTraceId() {
        return UUID.randomUUID().toString();
    }
}
