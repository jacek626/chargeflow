package pl.chargeflow.session.station;

import org.junit.jupiter.api.Test;
import pl.chargeflow.session.station.exception.InvalidChargingPointStatusTransitionException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargingPointDomainTest {

    // --- isAvailableForReservation ---

    @Test
    void isAvailableForReservation_whenActiveAndAvailable_returnsTrue() {
        ChargingPoint point = point(ChargingPointStatus.AVAILABLE, true);
        assertThat(point.isAvailableForReservation()).isTrue();
    }

    @Test
    void isAvailableForReservation_whenActiveAndOutOfService_returnsFalse() {
        ChargingPoint point = point(ChargingPointStatus.OUT_OF_SERVICE, true);
        assertThat(point.isAvailableForReservation()).isFalse();
    }

    @Test
    void isAvailableForReservation_whenActiveAndReserved_returnsFalse() {
        ChargingPoint point = point(ChargingPointStatus.RESERVED, true);
        assertThat(point.isAvailableForReservation()).isFalse();
    }

    @Test
    void isAvailableForReservation_whenInactiveAndAvailable_returnsFalse() {
        ChargingPoint point = point(ChargingPointStatus.AVAILABLE, false);
        assertThat(point.isAvailableForReservation()).isFalse();
    }

    // --- markOutOfService ---

    @Test
    void markOutOfService_whenAvailable_transitionsToOutOfService() {
        ChargingPoint point = point(ChargingPointStatus.AVAILABLE, true);
        point.markOutOfService();
        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.OUT_OF_SERVICE);
    }

    @Test
    void markOutOfService_whenReserved_throwsInvalidTransition() {
        ChargingPoint point = point(ChargingPointStatus.RESERVED, true);
        assertThatThrownBy(point::markOutOfService)
                .isInstanceOf(InvalidChargingPointStatusTransitionException.class)
                .hasMessageContaining("RESERVED")
                .hasMessageContaining("OUT_OF_SERVICE");
    }

    @Test
    void markOutOfService_whenCharging_throwsInvalidTransition() {
        ChargingPoint point = point(ChargingPointStatus.CHARGING, true);
        assertThatThrownBy(point::markOutOfService)
                .isInstanceOf(InvalidChargingPointStatusTransitionException.class)
                .hasMessageContaining("CHARGING")
                .hasMessageContaining("OUT_OF_SERVICE");
    }

    @Test
    void markOutOfService_whenAlreadyOutOfService_throwsInvalidTransition() {
        ChargingPoint point = point(ChargingPointStatus.OUT_OF_SERVICE, true);
        assertThatThrownBy(point::markOutOfService)
                .isInstanceOf(InvalidChargingPointStatusTransitionException.class);
    }

    // --- restoreToAvailable ---

    @Test
    void restoreToAvailable_whenOutOfService_transitionsToAvailable() {
        ChargingPoint point = point(ChargingPointStatus.OUT_OF_SERVICE, true);
        point.restoreToAvailable();
        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.AVAILABLE);
    }

    @Test
    void restoreToAvailable_whenAlreadyAvailable_throwsInvalidTransition() {
        ChargingPoint point = point(ChargingPointStatus.AVAILABLE, true);
        assertThatThrownBy(point::restoreToAvailable)
                .isInstanceOf(InvalidChargingPointStatusTransitionException.class)
                .hasMessageContaining("AVAILABLE");
    }

    @Test
    void restoreToAvailable_whenReserved_throwsInvalidTransition() {
        ChargingPoint point = point(ChargingPointStatus.RESERVED, true);
        assertThatThrownBy(point::restoreToAvailable)
                .isInstanceOf(InvalidChargingPointStatusTransitionException.class)
                .hasMessageContaining("RESERVED");
    }

    @Test
    void restoreToAvailable_whenCharging_throwsInvalidTransition() {
        ChargingPoint point = point(ChargingPointStatus.CHARGING, true);
        assertThatThrownBy(point::restoreToAvailable)
                .isInstanceOf(InvalidChargingPointStatusTransitionException.class)
                .hasMessageContaining("CHARGING");
    }

    // --- exception carries correct state info ---

    @Test
    void invalidTransitionException_carriesCurrentAndRequestedStatus() {
        ChargingPoint point = point(ChargingPointStatus.RESERVED, true);
        try {
            point.markOutOfService();
        } catch (InvalidChargingPointStatusTransitionException ex) {
            assertThat(ex.getCurrentStatus()).isEqualTo(ChargingPointStatus.RESERVED);
            assertThat(ex.getRequestedStatus()).isEqualTo(ChargingPointStatus.OUT_OF_SERVICE);
            assertThat(ex.getErrorCode()).isEqualTo("INVALID_CHARGING_POINT_STATUS_TRANSITION");
        }
    }

    // --- reserve ---

    @Test
    void reserve_whenAvailableAndActive_transitionsToReserved() {
        ChargingPoint point = point(ChargingPointStatus.AVAILABLE, true);
        point.reserve();
        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.RESERVED);
    }

    @Test
    void reserve_whenOutOfService_throwsNotAvailable() {
        ChargingPoint point = point(ChargingPointStatus.OUT_OF_SERVICE, true);
        assertThatThrownBy(point::reserve)
                .isInstanceOf(pl.chargeflow.session.station.exception.ChargingPointNotAvailableException.class);
    }

    @Test
    void reserve_whenAlreadyReserved_throwsNotAvailable() {
        ChargingPoint point = point(ChargingPointStatus.RESERVED, true);
        assertThatThrownBy(point::reserve)
                .isInstanceOf(pl.chargeflow.session.station.exception.ChargingPointNotAvailableException.class);
    }

    @Test
    void reserve_whenInactive_throwsNotAvailable() {
        ChargingPoint point = point(ChargingPointStatus.AVAILABLE, false);
        assertThatThrownBy(point::reserve)
                .isInstanceOf(pl.chargeflow.session.station.exception.ChargingPointNotAvailableException.class);
    }

    // --- releaseReservation ---

    @Test
    void releaseReservation_whenReserved_transitionsToAvailable() {
        ChargingPoint point = point(ChargingPointStatus.RESERVED, true);
        point.releaseReservation();
        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.AVAILABLE);
    }

    @Test
    void releaseReservation_whenAvailable_throwsInvalidTransition() {
        ChargingPoint point = point(ChargingPointStatus.AVAILABLE, true);
        assertThatThrownBy(point::releaseReservation)
                .isInstanceOf(InvalidChargingPointStatusTransitionException.class);
    }

    @Test
    void releaseReservation_whenCharging_throwsInvalidTransition() {
        ChargingPoint point = point(ChargingPointStatus.CHARGING, true);
        assertThatThrownBy(point::releaseReservation)
                .isInstanceOf(InvalidChargingPointStatusTransitionException.class);
    }

    // --- startCharging ---

    @Test
    void startCharging_whenReserved_transitionsToCharging() {
        ChargingPoint point = point(ChargingPointStatus.RESERVED, true);
        point.startCharging();
        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.CHARGING);
    }

    @Test
    void startCharging_whenAvailable_throwsInvalidTransition() {
        ChargingPoint point = point(ChargingPointStatus.AVAILABLE, true);
        assertThatThrownBy(point::startCharging)
                .isInstanceOf(InvalidChargingPointStatusTransitionException.class)
                .hasMessageContaining("AVAILABLE")
                .hasMessageContaining("CHARGING");
    }

    @Test
    void startCharging_whenCharging_throwsInvalidTransition() {
        ChargingPoint point = point(ChargingPointStatus.CHARGING, true);
        assertThatThrownBy(point::startCharging)
                .isInstanceOf(InvalidChargingPointStatusTransitionException.class);
    }

    // --- stopCharging ---

    @Test
    void stopCharging_whenCharging_transitionsToAvailable() {
        ChargingPoint point = point(ChargingPointStatus.CHARGING, true);
        point.stopCharging();
        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.AVAILABLE);
    }

    @Test
    void stopCharging_whenReserved_throwsInvalidTransition() {
        ChargingPoint point = point(ChargingPointStatus.RESERVED, true);
        assertThatThrownBy(point::stopCharging)
                .isInstanceOf(InvalidChargingPointStatusTransitionException.class)
                .hasMessageContaining("RESERVED");
    }

    @Test
    void stopCharging_whenAvailable_throwsInvalidTransition() {
        ChargingPoint point = point(ChargingPointStatus.AVAILABLE, true);
        assertThatThrownBy(point::stopCharging)
                .isInstanceOf(InvalidChargingPointStatusTransitionException.class);
    }

    // --- helpers ---

    private ChargingPoint point(ChargingPointStatus status, boolean active) {
        ChargingStation station = new ChargingStation("Test Station", "ul. Testowa 1", "Lublin",
                BigDecimal.valueOf(51.0), BigDecimal.valueOf(22.0), true);
        return new ChargingPoint(station, "TEST-01", ConnectorType.CCS,
                BigDecimal.valueOf(150), BigDecimal.valueOf(2.49), status, active);
    }
}
