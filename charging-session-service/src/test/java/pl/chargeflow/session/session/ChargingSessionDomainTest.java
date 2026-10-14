package pl.chargeflow.session.session;

import org.junit.jupiter.api.Test;
import pl.chargeflow.session.reservation.ChargingReservation;
import pl.chargeflow.session.reservation.ReservationStatus;
import pl.chargeflow.session.session.exception.InvalidChargingSessionStatusTransitionException;
import pl.chargeflow.session.station.ChargingPoint;
import pl.chargeflow.session.station.ChargingPointStatus;
import pl.chargeflow.session.station.ChargingStation;
import pl.chargeflow.session.station.ConnectorType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargingSessionDomainTest {

    @Test
    void newSession_hasStartedStatus() {
        assertThat(session().getStatus()).isEqualTo(SessionStatus.STARTED);
    }

    @Test
    void newSession_isActive() {
        assertThat(session().isActive()).isTrue();
    }

    @Test
    void newSession_capturesPricePerKwhFromPoint() {
        ChargingSession s = session();
        assertThat(s.getPricePerKwh()).isEqualByComparingTo(BigDecimal.valueOf(2.49));
    }

    // --- complete ---

    @Test
    void complete_whenStarted_transitionsToCompleted() {
        ChargingSession s = session();
        s.complete(BigDecimal.valueOf(20.0));
        assertThat(s.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(s.isActive()).isFalse();
    }

    @Test
    void complete_calculatesTotalCostCorrectly() {
        ChargingSession s = session();  // pricePerKwh = 2.49
        s.complete(BigDecimal.valueOf(10.0));
        // 10.0 × 2.49 = 24.90
        assertThat(s.getTotalCost()).isEqualByComparingTo(BigDecimal.valueOf(24.90));
    }

    @Test
    void complete_setsEnergyConsumedKwh() {
        ChargingSession s = session();
        s.complete(BigDecimal.valueOf(15.5));
        assertThat(s.getEnergyConsumedKwh()).isEqualByComparingTo(BigDecimal.valueOf(15.5));
    }

    @Test
    void complete_setsStoppedAt() {
        ChargingSession s = session();
        s.complete(BigDecimal.valueOf(10.0));
        assertThat(s.getStoppedAt()).isNotNull().isAfterOrEqualTo(s.getStartedAt());
    }

    @Test
    void complete_whenAlreadyCompleted_throwsInvalidTransition() {
        ChargingSession s = session();
        s.complete(BigDecimal.valueOf(10.0));
        assertThatThrownBy(() -> s.complete(BigDecimal.valueOf(5.0)))
                .isInstanceOf(InvalidChargingSessionStatusTransitionException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void complete_whenFailed_throwsInvalidTransition() {
        ChargingSession s = session();
        s.fail();
        assertThatThrownBy(() -> s.complete(BigDecimal.valueOf(5.0)))
                .isInstanceOf(InvalidChargingSessionStatusTransitionException.class);
    }

    // --- fail ---

    @Test
    void fail_whenStarted_transitionsToFailed() {
        ChargingSession s = session();
        s.fail();
        assertThat(s.getStatus()).isEqualTo(SessionStatus.FAILED);
        assertThat(s.isActive()).isFalse();
    }

    @Test
    void fail_setsStoppedAt() {
        ChargingSession s = session();
        s.fail();
        assertThat(s.getStoppedAt()).isNotNull();
    }

    @Test
    void fail_whenAlreadyCompleted_throwsInvalidTransition() {
        ChargingSession s = session();
        s.complete(BigDecimal.valueOf(10.0));
        assertThatThrownBy(s::fail)
                .isInstanceOf(InvalidChargingSessionStatusTransitionException.class);
    }

    @Test
    void invalidTransitionException_hasCorrectMetadata() {
        ChargingSession s = session();
        s.complete(BigDecimal.valueOf(10.0));
        try {
            s.complete(BigDecimal.valueOf(5.0));
        } catch (InvalidChargingSessionStatusTransitionException ex) {
            assertThat(ex.getErrorCode()).isEqualTo("INVALID_CHARGING_SESSION_STATUS_TRANSITION");
            assertThat(ex.getCurrentStatus()).isEqualTo(SessionStatus.COMPLETED);
            assertThat(ex.getRequestedStatus()).isEqualTo(SessionStatus.COMPLETED);
        }
    }

    // --- helpers ---

    private ChargingSession session() {
        ChargingStation station = new ChargingStation("Test", "ul. X", "Lublin",
                BigDecimal.valueOf(51.0), BigDecimal.valueOf(22.0), true);
        ChargingPoint point = new ChargingPoint(station, "TST-01", ConnectorType.CCS,
                BigDecimal.valueOf(150), BigDecimal.valueOf(2.49),
                ChargingPointStatus.CHARGING, true);
        Instant now = Instant.now();
        ChargingReservation reservation = new ChargingReservation(
                "cust-1", "cust@example.com", point, now.minus(5, ChronoUnit.MINUTES),
                now.plus(10, ChronoUnit.MINUTES));
        return new ChargingSession("cust-1", point, reservation, now);
    }
}
