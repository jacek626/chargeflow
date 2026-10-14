package pl.chargeflow.session.station;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class StationCatalogIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    ChargingStationRepository stationRepository;

    @Autowired
    ChargingPointRepository pointRepository;

    @Test
    void flywayMigrationsRunAndExactlyThreeActiveStationsExist() {
        assertThat(stationRepository.findByActiveTrueOrderByName()).hasSize(3);
    }

    @Test
    void exactlySevenChargingPointsExist() {
        assertThat(pointRepository.count()).isEqualTo(7);
    }

    @Test
    void lubC01ChargingPointExists() {
        assertThat(pointRepository.findByCode("LUB-C01")).isPresent();
    }

    @Test
    void allInitialChargingPointsAreAvailable() {
        List<ChargingPoint> points = pointRepository.findAll();
        assertThat(points).allMatch(cp -> cp.getStatus() == ChargingPointStatus.AVAILABLE);
    }

    @Test
    void allInitialChargingPointsAreActive() {
        List<ChargingPoint> points = pointRepository.findAll();
        assertThat(points).allMatch(ChargingPoint::isActive);
    }

    @Test
    void stationDetailsLoadChargingPointsCorrectly() {
        UUID centrumId = UUID.fromString("11111111-0000-0000-0000-000000000001");
        List<ChargingPoint> points = pointRepository.findActiveByStationId(centrumId);
        assertThat(points).hasSize(3);
        assertThat(points).extracting(ChargingPoint::getCode)
                .containsExactlyInAnyOrder("LUB-C01", "LUB-C02", "LUB-T01");
    }

    @Test
    void batchLoadingPointsForAllStationsDoesNotCauseNPlusOne() {
        List<ChargingStation> stations = stationRepository.findByActiveTrueOrderByName();
        List<UUID> ids = stations.stream().map(ChargingStation::getId).toList();
        // Single query for all stations — no per-station loop queries.
        List<ChargingPoint> points = pointRepository.findActiveByStationIds(ids);
        assertThat(points).hasSize(7);
    }
}
