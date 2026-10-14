package pl.chargeflow.session.reservation;

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
import pl.chargeflow.session.reservation.api.ReservationResponse;
import pl.chargeflow.session.station.ChargingPoint;
import pl.chargeflow.session.station.ChargingPointRepository;
import pl.chargeflow.session.station.ChargingPointStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// @Transactional rolls back after each test — seed data is restored automatically.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Transactional
class ReservationExpirationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    ReservationService reservationService;

    @Autowired
    ReservationExpirationJob expirationJob;

    @Autowired
    ChargingReservationRepository reservationRepository;

    @Autowired
    ChargingPointRepository pointRepository;

    @PersistenceContext
    EntityManager entityManager;

    private static final UUID LUB_C01 = UUID.fromString("22222222-0000-0000-0000-000000000001");
    private static final UUID LUB_C02 = UUID.fromString("22222222-0000-0000-0000-000000000002");

    @Test
    void expireReservationsBefore_expiredReservation_transitionsToExpired() {
        ReservationResponse created = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        setValidUntilInPast(created.id());
        entityManager.flush();
        entityManager.clear();

        expirationJob.expireReservationsBefore(Instant.now());
        entityManager.flush();
        entityManager.clear();

        ChargingReservation reservation = reservationRepository.findById(created.id()).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void expireReservationsBefore_expiredReservation_releasesChargingPointToAvailable() {
        ReservationResponse created = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        setValidUntilInPast(created.id());
        entityManager.flush();
        entityManager.clear();

        expirationJob.expireReservationsBefore(Instant.now());
        entityManager.flush();
        entityManager.clear();

        ChargingPoint point = pointRepository.findById(LUB_C01).orElseThrow();
        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.AVAILABLE);
    }

    @Test
    void expireReservationsBefore_activeReservation_isNotExpired() {
        ReservationResponse created = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();
        entityManager.clear();

        // Run job now — validUntil is 15 min in the future, so it must NOT be expired.
        expirationJob.expireReservationsBefore(Instant.now());
        entityManager.flush();
        entityManager.clear();

        ChargingReservation reservation = reservationRepository.findById(created.id()).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CREATED);
    }

    @Test
    void expireReservationsBefore_cancelledReservation_isNotTouchedByExpiration() {
        ReservationResponse created = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        reservationService.cancelReservation(created.id(), "cust-1");
        setValidUntilInPast(created.id());
        entityManager.flush();
        entityManager.clear();

        expirationJob.expireReservationsBefore(Instant.now());
        entityManager.flush();
        entityManager.clear();

        // Already CANCELLED — expiration must not change it.
        ChargingReservation reservation = reservationRepository.findById(created.id()).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void expireReservationsBefore_multipleExpiredReservations_expiresAll() {
        ReservationResponse r1 = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        ReservationResponse r2 = reservationService.createReservation(LUB_C02, "cust-2", "cust2@example.com");
        setValidUntilInPast(r1.id());
        setValidUntilInPast(r2.id());
        entityManager.flush();
        entityManager.clear();

        expirationJob.expireReservationsBefore(Instant.now());
        entityManager.flush();
        entityManager.clear();

        assertThat(reservationRepository.findById(r1.id()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservationRepository.findById(r2.id()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(pointRepository.findById(LUB_C01).orElseThrow().getStatus())
                .isEqualTo(ChargingPointStatus.AVAILABLE);
        assertThat(pointRepository.findById(LUB_C02).orElseThrow().getStatus())
                .isEqualTo(ChargingPointStatus.AVAILABLE);
    }

    @Test
    void expireReservationsBefore_afterExpiration_pointIsAvailableForNewReservation() {
        ReservationResponse created = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        setValidUntilInPast(created.id());
        entityManager.flush();
        entityManager.clear();

        expirationJob.expireReservationsBefore(Instant.now());
        entityManager.flush();
        entityManager.clear();

        // New reservation for the same point must succeed.
        ReservationResponse newReservation = reservationService.createReservation(LUB_C01, "cust-2", "cust2@example.com");
        assertThat(newReservation.status()).isEqualTo(ReservationStatus.CREATED);
    }

    // --- helpers ---

    private void setValidUntilInPast(UUID reservationId) {
        entityManager.createQuery("""
                UPDATE ChargingReservation r
                SET r.validUntil = :past
                WHERE r.id = :id
                """)
                .setParameter("past", Instant.now().minus(1, ChronoUnit.MINUTES))
                .setParameter("id", reservationId)
                .executeUpdate();
    }
}
