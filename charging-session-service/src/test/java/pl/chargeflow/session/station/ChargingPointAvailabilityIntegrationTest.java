package pl.chargeflow.session.station;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// @Transactional rolls back after each test — seed data is restored automatically.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Transactional
class ChargingPointAvailabilityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    ChargingPointAdminService adminService;

    @Autowired
    ChargingPointRepository pointRepository;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void changeToOutOfServicePersistsInDatabase() {
        ChargingPoint point = pointRepository.findByCode("LUB-C01").orElseThrow();
        UUID id = point.getId();

        adminService.changeOperationalStatus(id, ChargingPointStatus.OUT_OF_SERVICE);

        entityManager.flush();
        entityManager.clear();

        ChargingPoint reloaded = pointRepository.findById(id).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChargingPointStatus.OUT_OF_SERVICE);
    }

    @Test
    void outOfServicePointIsNotAvailableForReservation() {
        ChargingPoint point = pointRepository.findByCode("LUB-C01").orElseThrow();

        adminService.changeOperationalStatus(point.getId(), ChargingPointStatus.OUT_OF_SERVICE);

        assertThat(point.isAvailableForReservation()).isFalse();
    }

    @Test
    void restoreToAvailablePersistsInDatabase() {
        ChargingPoint point = pointRepository.findByCode("LUB-C01").orElseThrow();
        UUID id = point.getId();

        adminService.changeOperationalStatus(id, ChargingPointStatus.OUT_OF_SERVICE);
        entityManager.flush();
        entityManager.clear();

        adminService.changeOperationalStatus(id, ChargingPointStatus.AVAILABLE);
        entityManager.flush();
        entityManager.clear();

        ChargingPoint reloaded = pointRepository.findById(id).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChargingPointStatus.AVAILABLE);
        assertThat(reloaded.isAvailableForReservation()).isTrue();
    }

    @Test
    void initiallyAvailablePointIsAvailableForReservation() {
        ChargingPoint point = pointRepository.findByCode("LUB-C01").orElseThrow();
        assertThat(point.isAvailableForReservation()).isTrue();
    }
}
