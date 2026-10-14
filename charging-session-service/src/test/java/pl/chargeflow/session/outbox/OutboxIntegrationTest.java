package pl.chargeflow.session.outbox;

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
import pl.chargeflow.session.event.DomainEventType;
import pl.chargeflow.session.reservation.ReservationService;
import pl.chargeflow.session.reservation.api.ReservationResponse;
import pl.chargeflow.session.session.ChargingSessionService;
import pl.chargeflow.session.session.api.ChargingSessionResponse;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// @Transactional rolls back each test; seed data is restored automatically.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Transactional
class OutboxIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    ReservationService reservationService;

    @Autowired
    ChargingSessionService sessionService;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    OutboxPoller outboxPoller;

    @PersistenceContext
    EntityManager entityManager;

    private static final UUID LUB_C01 = UUID.fromString("22222222-0000-0000-0000-000000000001");
    private static final UUID LUB_C02 = UUID.fromString("22222222-0000-0000-0000-000000000002");

    // --- createReservation writes outbox record atomically ---

    @Test
    void createReservation_persistsReservationCreatedOutboxEvent() {
        ReservationResponse created = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();

        OutboxEvent event = outboxEventByAggregate(created.id());
        assertThat(event.getEventType()).isEqualTo(DomainEventType.RESERVATION_CREATED);
        assertThat(event.getAggregateType()).isEqualTo(OutboxAggregateType.RESERVATION);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.NEW);
        assertThat(event.getPayload())
                .contains("customerId")
                .contains("chargingPointId")
                .contains("validUntil");
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getPublishedAt()).isNull();
    }

    // --- cancelReservation writes outbox record atomically ---

    @Test
    void cancelReservation_persistsReservationCancelledOutboxEvent() {
        ReservationResponse created = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();
        entityManager.clear();

        reservationService.cancelReservation(created.id(), "cust-1");
        entityManager.flush();

        List<DomainEventType> types = outboxEventsByAggregate(created.id())
                .stream().map(OutboxEvent::getEventType).toList();
        assertThat(types).containsExactlyInAnyOrder(
                DomainEventType.RESERVATION_CREATED,
                DomainEventType.RESERVATION_CANCELLED);
    }

    // --- startSession writes outbox record atomically ---

    @Test
    void startSession_persistsChargingStartedOutboxEvent() {
        ReservationResponse reservation = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();
        entityManager.clear();

        ChargingSessionResponse session = sessionService.startSession(reservation.id(), "cust-1");
        entityManager.flush();

        OutboxEvent started = outboxEventByAggregate(session.id());
        assertThat(started.getEventType()).isEqualTo(DomainEventType.CHARGING_STARTED);
        assertThat(started.getAggregateType()).isEqualTo(OutboxAggregateType.SESSION);
        assertThat(started.getPayload()).contains("reservationId");
    }

    // --- stopSession writes outbox record atomically ---

    @Test
    void stopSession_persistsChargingCompletedOutboxEvent() {
        ReservationResponse reservation = reservationService.createReservation(LUB_C01, "cust-1", "cust1@example.com");
        entityManager.flush();
        entityManager.clear();

        ChargingSessionResponse session = sessionService.startSession(reservation.id(), "cust-1");
        entityManager.flush();
        entityManager.clear();

        sessionService.stopSession(session.id(), "cust-1");
        entityManager.flush();

        // Session has both CHARGING_STARTED and CHARGING_COMPLETED in outbox
        List<DomainEventType> types = outboxEventsByAggregate(session.id())
                .stream().map(OutboxEvent::getEventType).toList();
        assertThat(types).containsExactlyInAnyOrder(
                DomainEventType.CHARGING_STARTED,
                DomainEventType.CHARGING_COMPLETED);

        OutboxEvent completed = outboxEventsByAggregate(session.id()).stream()
                .filter(e -> e.getEventType() == DomainEventType.CHARGING_COMPLETED)
                .findFirst().orElseThrow();
        assertThat(completed.getPayload()).contains("energyConsumedKwh").contains("totalCost");
    }

    // --- outbox poller marks records as PUBLISHED ---
    // Runs outside class-level @Transactional so commits are real; cleans up after itself.

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void outboxPoller_marksNewEventsAsPublished() {
        UUID pointId = UUID.fromString("22222222-0000-0000-0000-000000000007"); // LUB-F02 — dedicated to this test
        ReservationResponse created = reservationService.createReservation(pointId, "cust-poll", "poll@example.com");

        List<OutboxEvent> before = outboxEventsByAggregate(created.id());
        assertThat(before).hasSize(1);
        assertThat(before.getFirst().getStatus()).isEqualTo(OutboxEventStatus.NEW);

        outboxPoller.pollAndPublish();

        List<OutboxEvent> after = outboxEventsByAggregate(created.id());
        assertThat(after).hasSize(1);
        assertThat(after.getFirst().getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(after.getFirst().getPublishedAt()).isNotNull();

        // Clean up committed data so other tests are not affected
        outboxEventRepository.deleteAll(after);
    }

    // --- helpers ---

    private OutboxEvent outboxEventByAggregate(UUID aggregateId) {
        return outboxEventsByAggregate(aggregateId).stream().findFirst()
                .orElseThrow(() -> new AssertionError("No outbox event for aggregateId " + aggregateId));
    }

    @SuppressWarnings("unchecked")
    private List<OutboxEvent> outboxEventsByAggregate(UUID aggregateId) {
        return entityManager.createQuery(
                "SELECT e FROM OutboxEvent e WHERE e.aggregateId = :id", OutboxEvent.class)
                .setParameter("id", aggregateId)
                .getResultList();
    }
}
