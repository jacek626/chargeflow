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
import pl.chargeflow.session.station.exception.ChargingPointNotAvailableException;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// @Transactional rolls back after each test — seed data is restored automatically.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Transactional
class ReservationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    ReservationService reservationService;

    @Autowired
    ChargingReservationRepository reservationRepository;

    @Autowired
    ChargingPointRepository pointRepository;

    @PersistenceContext
    EntityManager entityManager;

    private static final UUID LUB_C01 = UUID.fromString("22222222-0000-0000-0000-000000000001");
    private static final UUID LUB_C02 = UUID.fromString("22222222-0000-0000-0000-000000000002");

    // --- create ---

    @Test
    void createReservation_persistsReservationInDatabase() {
        ReservationResponse response = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");

        entityManager.flush();
        entityManager.clear();

        ChargingReservation saved = reservationRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getCustomerId()).isEqualTo("cust-1");
        assertThat(saved.getStatus()).isEqualTo(ReservationStatus.CREATED);
        assertThat(saved.getChargingPoint().getId()).isEqualTo(LUB_C01);
        assertThat(saved.getValidUntil()).isAfter(saved.getReservedAt());
    }

    @Test
    void createReservation_setsChargingPointStatusToReserved() {
        reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");

        entityManager.flush();
        entityManager.clear();

        ChargingPoint point = pointRepository.findById(LUB_C01).orElseThrow();
        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.RESERVED);
    }

    @Test
    void createReservation_reservedPoint_throwsChargingPointNotAvailable() {
        reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> reservationService.createReservation(LUB_C01, "cust-2", "cust2@example.com"))
                .isInstanceOf(ChargingPointNotAvailableException.class);
    }

    // --- cancel ---

    @Test
    void cancelReservation_releasesChargingPointToAvailable() {
        ReservationResponse created = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();
        entityManager.clear();

        reservationService.cancelReservation(created.id(), "cust-1");
        entityManager.flush();
        entityManager.clear();

        ChargingReservation reservation = reservationRepository.findById(created.id()).orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);

        ChargingPoint point = pointRepository.findById(LUB_C01).orElseThrow();
        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.AVAILABLE);
    }

    @Test
    void cancelReservation_afterRelease_newReservationIsPossible() {
        ReservationResponse first = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();
        entityManager.clear();

        reservationService.cancelReservation(first.id(), "cust-1");
        entityManager.flush();
        entityManager.clear();

        ReservationResponse second = reservationService.createReservation(LUB_C01, "cust-2", "cust2@example.com");
        assertThat(second.status()).isEqualTo(ReservationStatus.CREATED);
    }

    // --- list / get ---

    @Test
    void listReservations_returnsOnlyReservationsForCustomer() {
        reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        reservationService.createReservation(LUB_C02, "cust-2", "cust2@example.com");

        entityManager.flush();
        entityManager.clear();

        assertThat(reservationService.listReservations("cust-1")).hasSize(1);
        assertThat(reservationService.listReservations("cust-2")).hasSize(1);
    }

    @Test
    void getReservation_returnsCorrectReservation() {
        ReservationResponse created = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();
        entityManager.clear();

        ReservationResponse fetched = reservationService.getReservation(created.id(), "cust-1");
        assertThat(fetched.id()).isEqualTo(created.id());
        assertThat(fetched.customerId()).isEqualTo("cust-1");
        assertThat(fetched.status()).isEqualTo(ReservationStatus.CREATED);
    }

    // --- concurrent reservation (optimistic locking) ---
    // This test runs outside of the class-level @Transactional so each service call uses its own tx.

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void createReservation_concurrentAttemptsOnSamePoint_exactlyOneSucceeds() throws InterruptedException {
        UUID pointId = UUID.fromString("22222222-0000-0000-0000-000000000006"); // LUB-F01

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger conflicts = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(2);

        Runnable attempt = () -> {
            try {
                startLatch.await();
                reservationService.createReservation(pointId, "cust-thread-" + Thread.currentThread().getId(), "thread@example.com");
                successes.incrementAndGet();
            } catch (ChargingPointNotAvailableException
                   | org.springframework.dao.OptimisticLockingFailureException
                   | org.springframework.dao.DataIntegrityViolationException e) {
                // All three indicate "point no longer available": domain guard, optimistic lock,
                // or partial unique index (reservation table) when Hibernate INSERTs before UPDATEs.
                conflicts.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(attempt);
        executor.submit(attempt);
        startLatch.countDown();

        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
    }
}
