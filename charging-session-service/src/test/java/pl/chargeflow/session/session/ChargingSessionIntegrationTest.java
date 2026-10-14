package pl.chargeflow.session.session;

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
import pl.chargeflow.session.reservation.ChargingReservation;
import pl.chargeflow.session.reservation.ReservationService;
import pl.chargeflow.session.reservation.ReservationStatus;
import pl.chargeflow.session.reservation.api.ReservationResponse;
import pl.chargeflow.session.reservation.exception.ReservationAccessDeniedException;
import pl.chargeflow.session.session.api.ChargingSessionResponse;
import pl.chargeflow.session.session.exception.ChargingSessionAccessDeniedException;
import pl.chargeflow.session.station.ChargingPoint;
import pl.chargeflow.session.station.ChargingPointRepository;
import pl.chargeflow.session.station.ChargingPointStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// @Transactional rolls back after each test — seed data is restored automatically.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Transactional
class ChargingSessionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    ChargingSessionService sessionService;

    @Autowired
    ReservationService reservationService;

    @Autowired
    ChargingSessionRepository sessionRepository;

    @Autowired
    ChargingPointRepository pointRepository;

    @PersistenceContext
    EntityManager entityManager;

    private static final UUID LUB_C01 = UUID.fromString("22222222-0000-0000-0000-000000000001");
    private static final UUID LUB_C02 = UUID.fromString("22222222-0000-0000-0000-000000000002");

    // --- startSession ---

    @Test
    void startSession_persistsSessionInDatabase() {
        ReservationResponse reservation = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();
        entityManager.clear();

        ChargingSessionResponse response = sessionService.startSession(reservation.id(), "cust-1");
        entityManager.flush();
        entityManager.clear();

        ChargingSession saved = sessionRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getCustomerId()).isEqualTo("cust-1");
        assertThat(saved.getStatus()).isEqualTo(SessionStatus.STARTED);
        assertThat(saved.getStartedAt()).isNotNull();
        assertThat(saved.getPricePerKwh()).isPositive();
    }

    @Test
    void startSession_setsPointToChargingAndReservationToUsed() {
        ReservationResponse reservationResp = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();
        entityManager.clear();

        sessionService.startSession(reservationResp.id(), "cust-1");
        entityManager.flush();
        entityManager.clear();

        ChargingPoint point = pointRepository.findById(LUB_C01).orElseThrow();
        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.CHARGING);

        ChargingReservation reservation = entityManager.find(ChargingReservation.class, reservationResp.id());
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.USED);
    }

    @Test
    void startSession_wrongCustomer_throwsAccessDenied() {
        ReservationResponse reservation = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> sessionService.startSession(reservation.id(), "cust-2"))
                .isInstanceOf(ReservationAccessDeniedException.class);
    }

    // --- stopSession ---

    @Test
    void stopSession_completesSessionAndReleasesPoint() {
        ReservationResponse reservationResp = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();
        entityManager.clear();

        ChargingSessionResponse started = sessionService.startSession(reservationResp.id(), "cust-1");
        entityManager.flush();
        entityManager.clear();

        ChargingSessionResponse stopped = sessionService.stopSession(started.id(), "cust-1");
        entityManager.flush();
        entityManager.clear();

        assertThat(stopped.status()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(stopped.stoppedAt()).isNotNull();

        ChargingPoint point = pointRepository.findById(LUB_C01).orElseThrow();
        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.AVAILABLE);
    }

    @Test
    void stopSession_calculatesTotalCost() {
        ReservationResponse reservationResp = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();
        entityManager.clear();

        ChargingSessionResponse started = sessionService.startSession(reservationResp.id(), "cust-1");
        entityManager.flush();
        entityManager.clear();

        ChargingSessionResponse stopped = sessionService.stopSession(started.id(), "cust-1");

        assertThat(stopped.energyConsumedKwh()).isNotNull().isPositive();
        assertThat(stopped.totalCost()).isNotNull().isPositive();
        assertThat(stopped.pricePerKwh()).isNotNull().isPositive();
    }

    @Test
    void stopSession_wrongCustomer_throwsAccessDenied() {
        ReservationResponse reservationResp = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();
        entityManager.clear();

        ChargingSessionResponse started = sessionService.startSession(reservationResp.id(), "cust-1");
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> sessionService.stopSession(started.id(), "cust-2"))
                .isInstanceOf(ChargingSessionAccessDeniedException.class);
    }

    // --- listSessions / getSession ---

    @Test
    void listSessions_returnsOnlySessionsForCustomer() {
        ReservationResponse r1 = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        ReservationResponse r2 = reservationService.createReservation(LUB_C02, "cust-2", "cust2@example.com");
        entityManager.flush();
        entityManager.clear();

        sessionService.startSession(r1.id(), "cust-1");
        sessionService.startSession(r2.id(), "cust-2");
        entityManager.flush();
        entityManager.clear();

        List<ChargingSessionResponse> cust1Sessions = sessionService.listSessions("cust-1");
        List<ChargingSessionResponse> cust2Sessions = sessionService.listSessions("cust-2");

        assertThat(cust1Sessions).hasSize(1);
        assertThat(cust1Sessions.getFirst().customerId()).isEqualTo("cust-1");
        assertThat(cust2Sessions).hasSize(1);
        assertThat(cust2Sessions.getFirst().customerId()).isEqualTo("cust-2");
    }

    @Test
    void getSession_returnsCorrectSession() {
        ReservationResponse reservationResp = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();
        entityManager.clear();

        ChargingSessionResponse started = sessionService.startSession(reservationResp.id(), "cust-1");
        entityManager.flush();
        entityManager.clear();

        ChargingSessionResponse fetched = sessionService.getSession(started.id(), "cust-1");
        assertThat(fetched.id()).isEqualTo(started.id());
        assertThat(fetched.customerId()).isEqualTo("cust-1");
        assertThat(fetched.status()).isEqualTo(SessionStatus.STARTED);
    }
}
