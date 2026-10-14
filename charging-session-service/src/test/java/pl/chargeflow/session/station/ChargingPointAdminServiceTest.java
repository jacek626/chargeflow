package pl.chargeflow.session.station;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.chargeflow.session.station.api.ChargingPointResponse;
import pl.chargeflow.session.station.exception.ChargingPointNotFoundException;
import pl.chargeflow.session.station.exception.InvalidChargingPointStatusTransitionException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChargingPointAdminServiceTest {

    private ChargingPointRepository pointRepository;
    private ChargingPointAdminService service;

    @BeforeEach
    void setUp() {
        pointRepository = mock(ChargingPointRepository.class);
        service = new ChargingPointAdminService(pointRepository, new StationMapper());
    }

    @Test
    void changeOperationalStatus_availableToOutOfService_returnsOutOfServiceResponse() {
        UUID id = UUID.randomUUID();
        ChargingPoint point = persistedPoint(id, ChargingPointStatus.AVAILABLE);
        when(pointRepository.findById(id)).thenReturn(Optional.of(point));

        ChargingPointResponse response = service.changeOperationalStatus(id, ChargingPointStatus.OUT_OF_SERVICE);

        assertThat(response.status()).isEqualTo(ChargingPointStatus.OUT_OF_SERVICE);
        assertThat(response.id()).isEqualTo(id);
    }

    @Test
    void changeOperationalStatus_outOfServiceToAvailable_returnsAvailableResponse() {
        UUID id = UUID.randomUUID();
        ChargingPoint point = persistedPoint(id, ChargingPointStatus.OUT_OF_SERVICE);
        when(pointRepository.findById(id)).thenReturn(Optional.of(point));

        ChargingPointResponse response = service.changeOperationalStatus(id, ChargingPointStatus.AVAILABLE);

        assertThat(response.status()).isEqualTo(ChargingPointStatus.AVAILABLE);
    }

    @Test
    void changeOperationalStatus_reservedPointToOutOfService_throwsInvalidTransition() {
        UUID id = UUID.randomUUID();
        ChargingPoint point = persistedPoint(id, ChargingPointStatus.RESERVED);
        when(pointRepository.findById(id)).thenReturn(Optional.of(point));

        assertThatThrownBy(() -> service.changeOperationalStatus(id, ChargingPointStatus.OUT_OF_SERVICE))
                .isInstanceOf(InvalidChargingPointStatusTransitionException.class);
    }

    @Test
    void changeOperationalStatus_chargingPointToOutOfService_throwsInvalidTransition() {
        UUID id = UUID.randomUUID();
        ChargingPoint point = persistedPoint(id, ChargingPointStatus.CHARGING);
        when(pointRepository.findById(id)).thenReturn(Optional.of(point));

        assertThatThrownBy(() -> service.changeOperationalStatus(id, ChargingPointStatus.OUT_OF_SERVICE))
                .isInstanceOf(InvalidChargingPointStatusTransitionException.class);
    }

    @Test
    void changeOperationalStatus_requestedReservedStatus_throwsInvalidTransition() {
        UUID id = UUID.randomUUID();
        ChargingPoint point = persistedPoint(id, ChargingPointStatus.AVAILABLE);
        when(pointRepository.findById(id)).thenReturn(Optional.of(point));

        assertThatThrownBy(() -> service.changeOperationalStatus(id, ChargingPointStatus.RESERVED))
                .isInstanceOf(InvalidChargingPointStatusTransitionException.class);
    }

    @Test
    void changeOperationalStatus_unknownPoint_throwsNotFoundException() {
        UUID unknownId = UUID.randomUUID();
        when(pointRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeOperationalStatus(unknownId, ChargingPointStatus.OUT_OF_SERVICE))
                .isInstanceOf(ChargingPointNotFoundException.class);
    }

    @Test
    void changeOperationalStatus_loadsPointFromRepository() {
        UUID id = UUID.randomUUID();
        ChargingPoint point = persistedPoint(id, ChargingPointStatus.AVAILABLE);
        when(pointRepository.findById(id)).thenReturn(Optional.of(point));

        service.changeOperationalStatus(id, ChargingPointStatus.OUT_OF_SERVICE);

        verify(pointRepository).findById(id);
    }

    // --- helpers ---

    private ChargingPoint persistedPoint(UUID id, ChargingPointStatus status) {
        ChargingStation station = new ChargingStation("Test Station", "ul. Testowa 1", "Lublin",
                BigDecimal.valueOf(51.0), BigDecimal.valueOf(22.0), true);
        ChargingPoint point = new ChargingPoint(station, "TEST-01", ConnectorType.CCS,
                BigDecimal.valueOf(150), BigDecimal.valueOf(2.49), status, true);
        ChargingPoint spy = spy(point);
        doReturn(id).when(spy).getId();
        return spy;
    }
}
