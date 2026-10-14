package pl.chargeflow.session.station;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.chargeflow.session.station.api.ChargingStationDetailsResponse;
import pl.chargeflow.session.station.api.ChargingStationSummaryResponse;
import pl.chargeflow.session.station.exception.ChargingStationNotFoundException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class ChargingStationServiceTest {

    private ChargingStationRepository stationRepository;
    private ChargingPointRepository pointRepository;
    private ChargingStationService service;

    @BeforeEach
    void setUp() {
        stationRepository = mock(ChargingStationRepository.class);
        pointRepository = mock(ChargingPointRepository.class);
        service = new ChargingStationService(stationRepository, pointRepository, new StationMapper());
    }

    @Test
    void findAllActiveMapsStationSummariesCorrectly() {
        // Use a spy to represent a persisted station — Hibernate would have set the ID.
        ChargingStation station = persistedStation("Lublin Centrum", "Lublin");
        ChargingPoint available = point(station, ChargingPointStatus.AVAILABLE);
        ChargingPoint reserved  = point(station, ChargingPointStatus.RESERVED);

        when(stationRepository.findByActiveTrueOrderByName()).thenReturn(List.of(station));
        when(pointRepository.findActiveByStationIds(anyList())).thenReturn(List.of(available, reserved));

        List<ChargingStationSummaryResponse> result = service.findAllActive();

        assertThat(result).hasSize(1);
        ChargingStationSummaryResponse summary = result.getFirst();
        assertThat(summary.name()).isEqualTo("Lublin Centrum");
        assertThat(summary.city()).isEqualTo("Lublin");
        assertThat(summary.chargingPointCount()).isEqualTo(2);
        assertThat(summary.availableChargingPointCount()).isEqualTo(1);
    }

    @Test
    void findAllActiveReturnsEmptyListWhenNoActiveStations() {
        when(stationRepository.findByActiveTrueOrderByName()).thenReturn(List.of());
        assertThat(service.findAllActive()).isEmpty();
    }

    @Test
    void findByIdMapsStationDetailsCorrectly() {
        UUID id = UUID.randomUUID();
        ChargingStation station = persistedStation("Lublin Felin", "Lublin");
        ChargingPoint point = point(station, ChargingPointStatus.AVAILABLE);

        when(stationRepository.findByIdAndActiveTrue(id)).thenReturn(Optional.of(station));
        when(pointRepository.findActiveByStationId(id)).thenReturn(List.of(point));

        ChargingStationDetailsResponse details = service.findById(id);

        assertThat(details.name()).isEqualTo("Lublin Felin");
        assertThat(details.chargingPoints()).hasSize(1);
        assertThat(details.chargingPoints().getFirst().status()).isEqualTo(ChargingPointStatus.AVAILABLE);
    }

    @Test
    void findByIdThrowsWhenStationNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(stationRepository.findByIdAndActiveTrue(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(unknownId))
                .isInstanceOf(ChargingStationNotFoundException.class)
                .hasMessage("Charging station was not found.");
    }

    @Test
    void unknownStationExceptionHasCorrectErrorCode() {
        UUID unknownId = UUID.randomUUID();
        when(stationRepository.findByIdAndActiveTrue(unknownId)).thenReturn(Optional.empty());

        ChargingStationNotFoundException ex = org.junit.jupiter.api.Assertions.assertThrows(
                ChargingStationNotFoundException.class, () -> service.findById(unknownId));
        assertThat(ex.getErrorCode()).isEqualTo("CHARGING_STATION_NOT_FOUND");
    }

    // --- helpers ---

    /**
     * Creates a station spy representing persisted state: getId() returns a stable non-null UUID,
     * exactly as Hibernate would set it after INSERT. Avoids adding production behaviour to the
     * entity solely to satisfy unit tests.
     */
    private ChargingStation persistedStation(String name, String city) {
        ChargingStation station = new ChargingStation(name, "Demo address", city,
                BigDecimal.valueOf(51.0), BigDecimal.valueOf(22.0), true);
        ChargingStation spy = spy(station);
        doReturn(UUID.randomUUID()).when(spy).getId();
        return spy;
    }

    private ChargingPoint point(ChargingStation station, ChargingPointStatus status) {
        return new ChargingPoint(station, "TST-01", ConnectorType.CCS,
                BigDecimal.valueOf(150), BigDecimal.valueOf(2.49), status, true);
    }
}
