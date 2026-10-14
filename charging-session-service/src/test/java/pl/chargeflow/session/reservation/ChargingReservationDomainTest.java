package pl.chargeflow.session.reservation;

import org.junit.jupiter.api.Test;
import pl.chargeflow.session.reservation.exception.InvalidReservationStatusTransitionException;
import pl.chargeflow.session.station.ChargingPoint;
import pl.chargeflow.session.station.ChargingPointStatus;
import pl.chargeflow.session.station.ChargingStation;
import pl.chargeflow.session.station.ConnectorType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargingReservationDomainTest {

    @Test
    void newReservation_hasCreatedStatus() {
        assertThat(reservation().getStatus()).isEqualTo(ReservationStatus.CREATED);
    }

    @Test
    void newReservation_isActive() {
        assertThat(reservation().isActive()).isTrue();
    }

    @Test
    void newReservation_validUntilIsFifteenMinutesAfterReservedAt() {
        Instant reservedAt = Instant.now();
        ChargingReservation r = reservation(reservedAt);
        assertThat(r.getValidUntil()).isEqualTo(reservedAt.plus(15, ChronoUnit.MINUTES));
    }

    @Test
    void cancel_whenCreated_transitionsToCancelled() {
        ChargingReservation r = reservation();
        r.cancel();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r.isActive()).isFalse();
    }

    @Test
    void cancel_whenUsed_throwsInvalidTransition() {
        ChargingReservation r = reservationWithStatus(ReservationStatus.USED);
        assertThatThrownBy(r::cancel)
                .isInstanceOf(InvalidReservationStatusTransitionException.class)
                .hasMessageContaining("USED")
                .hasMessageContaining("CANCELLED");
    }

    @Test
    void cancel_whenAlreadyCancelled_throwsInvalidTransition() {
        ChargingReservation r = reservationWithStatus(ReservationStatus.CANCELLED);
        assertThatThrownBy(r::cancel)
                .isInstanceOf(InvalidReservationStatusTransitionException.class);
    }

    @Test
    void cancel_whenExpired_throwsInvalidTransition() {
        ChargingReservation r = reservationWithStatus(ReservationStatus.EXPIRED);
        assertThatThrownBy(r::cancel)
                .isInstanceOf(InvalidReservationStatusTransitionException.class);
    }

    // --- markUsed ---

    @Test
    void markUsed_whenCreated_transitionsToUsed() {
        ChargingReservation r = reservation();
        r.markUsed();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.USED);
        assertThat(r.isActive()).isFalse();
    }

    @Test
    void markUsed_whenCancelled_throwsInvalidTransition() {
        ChargingReservation r = reservationWithStatus(ReservationStatus.CANCELLED);
        assertThatThrownBy(r::markUsed)
                .isInstanceOf(InvalidReservationStatusTransitionException.class);
    }

    @Test
    void markUsed_whenAlreadyUsed_throwsInvalidTransition() {
        ChargingReservation r = reservationWithStatus(ReservationStatus.USED);
        assertThatThrownBy(r::markUsed)
                .isInstanceOf(InvalidReservationStatusTransitionException.class)
                .hasMessageContaining("USED");
    }

    // --- expire ---

    @Test
    void expire_whenCreated_transitionsToExpired() {
        ChargingReservation r = reservation();
        r.expire();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(r.isActive()).isFalse();
    }

    @Test
    void expire_whenAlreadyCancelled_throwsInvalidTransition() {
        ChargingReservation r = reservationWithStatus(ReservationStatus.CANCELLED);
        assertThatThrownBy(r::expire)
                .isInstanceOf(InvalidReservationStatusTransitionException.class);
    }

    @Test
    void expire_whenAlreadyExpired_throwsInvalidTransition() {
        ChargingReservation r = reservationWithStatus(ReservationStatus.EXPIRED);
        assertThatThrownBy(r::expire)
                .isInstanceOf(InvalidReservationStatusTransitionException.class);
    }

    @Test
    void expire_whenUsed_throwsInvalidTransition() {
        ChargingReservation r = reservationWithStatus(ReservationStatus.USED);
        assertThatThrownBy(r::expire)
                .isInstanceOf(InvalidReservationStatusTransitionException.class)
                .hasMessageContaining("USED")
                .hasMessageContaining("EXPIRED");
    }

    @Test
    void invalidTransitionException_hasCorrectMetadata() {
        ChargingReservation r = reservationWithStatus(ReservationStatus.USED);
        try {
            r.cancel();
        } catch (InvalidReservationStatusTransitionException ex) {
            assertThat(ex.getErrorCode()).isEqualTo("INVALID_RESERVATION_STATUS_TRANSITION");
            assertThat(ex.getCurrentStatus()).isEqualTo(ReservationStatus.USED);
            assertThat(ex.getRequestedStatus()).isEqualTo(ReservationStatus.CANCELLED);
        }
    }

    // --- helpers ---

    private ChargingReservation reservation() {
        return reservation(Instant.now());
    }

    private ChargingReservation reservation(Instant reservedAt) {
        ChargingStation station = new ChargingStation("Test", "ul. X", "Lublin",
                BigDecimal.valueOf(51.0), BigDecimal.valueOf(22.0), true);
        ChargingPoint point = new ChargingPoint(station, "TEST-01", ConnectorType.CCS,
                BigDecimal.valueOf(150), BigDecimal.valueOf(2.49),
                ChargingPointStatus.RESERVED, true);
        return new ChargingReservation("customer-1", "customer@example.com", point, reservedAt,
                reservedAt.plus(15, ChronoUnit.MINUTES));
    }

    private ChargingReservation reservationWithStatus(ReservationStatus status) {
        ChargingReservation r = reservation();
        try {
            java.lang.reflect.Field f = ChargingReservation.class.getDeclaredField("status");
            f.setAccessible(true);
            f.set(r, status);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Test setup failed", e);
        }
        return r;
    }
}
