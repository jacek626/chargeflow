package pl.chargeflow.session.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.chargeflow.session.event.DomainEventType;
import pl.chargeflow.session.reservation.ChargingReservation;
import pl.chargeflow.session.reservation.ReservationStatus;
import pl.chargeflow.session.session.ChargingSession;
import pl.chargeflow.session.station.ChargingPoint;
import pl.chargeflow.session.station.ChargingPointStatus;
import pl.chargeflow.session.station.ChargingStation;
import pl.chargeflow.session.station.ConnectorType;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxEventServiceTest {

    private OutboxEventRepository repository;
    private OutboxEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new OutboxEventService(repository, new ObjectMapper());
    }

    // --- saveReservationCreated ---

    @Test
    void saveReservationCreated_savesOutboxEventWithCorrectFields() {
        ChargingReservation reservation = persistedReservation(UUID.randomUUID(), "cust-1",
                reservedPoint(UUID.randomUUID()));

        service.saveReservationCreated(reservation);

        verify(repository).save(argThat(e ->
                e.getEventType() == DomainEventType.RESERVATION_CREATED
                && e.getAggregateType() == OutboxAggregateType.RESERVATION
                && e.getAggregateId().equals(reservation.getId())
                && e.getStatus() == OutboxEventStatus.NEW
                && e.getPayload().contains("customerId")
                && e.getPayload().contains("chargingPointId")
                && e.getPayload().contains("validUntil")
        ));
    }

    // --- saveReservationCancelled ---

    @Test
    void saveReservationCancelled_savesOutboxEventWithCorrectType() {
        ChargingReservation reservation = persistedReservation(UUID.randomUUID(), "cust-1",
                reservedPoint(UUID.randomUUID()));

        service.saveReservationCancelled(reservation);

        verify(repository).save(argThat(e ->
                e.getEventType() == DomainEventType.RESERVATION_CANCELLED
                && e.getAggregateType() == OutboxAggregateType.RESERVATION
        ));
    }

    // --- saveReservationExpired ---

    @Test
    void saveReservationExpired_savesOutboxEventWithCorrectType() {
        ChargingReservation reservation = persistedReservation(UUID.randomUUID(), "cust-1",
                reservedPoint(UUID.randomUUID()));

        service.saveReservationExpired(reservation);

        verify(repository).save(argThat(e ->
                e.getEventType() == DomainEventType.RESERVATION_EXPIRED
        ));
    }

    // --- saveChargingStarted ---

    @Test
    void saveChargingStarted_savesOutboxEventWithCorrectFields() {
        ChargingSession session = persistedSession(UUID.randomUUID(), "cust-1");

        service.saveChargingStarted(session);

        verify(repository).save(argThat(e ->
                e.getEventType() == DomainEventType.CHARGING_STARTED
                && e.getAggregateType() == OutboxAggregateType.SESSION
                && e.getAggregateId().equals(session.getId())
                && e.getPayload().contains("reservationId")
        ));
    }

    // --- saveChargingCompleted ---

    @Test
    void saveChargingCompleted_savesOutboxEventWithCostFields() {
        ChargingSession session = persistedSession(UUID.randomUUID(), "cust-1");
        session.complete(BigDecimal.valueOf(20.0));

        service.saveChargingCompleted(session);

        verify(repository).save(argThat(e ->
                e.getEventType() == DomainEventType.CHARGING_COMPLETED
                && e.getPayload().contains("energyConsumedKwh")
                && e.getPayload().contains("totalCost")
        ));
    }

    // --- payload is valid JSON ---

    @Test
    void saveReservationCreated_payloadIsValidJson() throws Exception {
        ChargingReservation reservation = persistedReservation(UUID.randomUUID(), "cust-1",
                reservedPoint(UUID.randomUUID()));

        service.saveReservationCreated(reservation);

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        // Should not throw — payload must be valid JSON
        new ObjectMapper().readTree(captor.getValue().getPayload());
    }

    // --- outbox record defaults ---

    @Test
    void savedEvent_hasNewStatusAndCreatedAt() {
        ChargingReservation reservation = persistedReservation(UUID.randomUUID(), "cust-1",
                reservedPoint(UUID.randomUUID()));

        service.saveReservationCreated(reservation);

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.NEW);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getPublishedAt()).isNull();
    }

    // --- helpers ---

    private ChargingPoint reservedPoint(UUID id) {
        ChargingStation station = new ChargingStation("Test", "ul. X", "Lublin",
                BigDecimal.valueOf(51.0), BigDecimal.valueOf(22.0), true);
        ChargingPoint point = new ChargingPoint(station, "TST-01", ConnectorType.CCS,
                BigDecimal.valueOf(150), BigDecimal.valueOf(2.49), ChargingPointStatus.RESERVED, true);
        ChargingPoint spied = spy(point);
        doReturn(id).when(spied).getId();
        return spied;
    }

    private ChargingReservation persistedReservation(UUID id, String customerId, ChargingPoint point) {
        Instant now = Instant.now();
        ChargingReservation r = new ChargingReservation(customerId, "cust@example.com", point, now, now.plus(15, ChronoUnit.MINUTES));
        ChargingReservation spied = spy(r);
        doReturn(id).when(spied).getId();
        return spied;
    }

    private ChargingSession persistedSession(UUID id, String customerId) {
        ChargingPoint point = reservedPoint(UUID.randomUUID());
        ChargingReservation reservation = persistedReservation(UUID.randomUUID(), customerId, point);
        // move point to CHARGING so session constructor sees it
        try {
            java.lang.reflect.Field f = ChargingPoint.class.getDeclaredField("status");
            f.setAccessible(true);
            f.set(point, ChargingPointStatus.CHARGING);
            java.lang.reflect.Field rs = ChargingReservation.class.getDeclaredField("status");
            rs.setAccessible(true);
            rs.set(reservation, ReservationStatus.USED);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        ChargingSession session = new ChargingSession(customerId, point, reservation, Instant.now());
        ChargingSession spied = spy(session);
        doReturn(id).when(spied).getId();
        return spied;
    }

    private static <T> T argThat(java.util.function.Predicate<T> pred) {
        return org.mockito.ArgumentMatchers.argThat(pred::test);
    }
}
