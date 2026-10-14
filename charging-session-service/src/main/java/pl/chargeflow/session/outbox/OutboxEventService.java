package pl.chargeflow.session.outbox;

import org.springframework.stereotype.Component;
import pl.chargeflow.session.event.DomainEventType;
import pl.chargeflow.session.event.payload.ChargingCompletedPayload;
import pl.chargeflow.session.event.payload.ChargingFailedPayload;
import pl.chargeflow.session.event.payload.ChargingStartedPayload;
import pl.chargeflow.session.event.payload.ReservationCancelledPayload;
import pl.chargeflow.session.event.payload.ReservationCreatedPayload;
import pl.chargeflow.session.event.payload.ReservationExpiredPayload;
import pl.chargeflow.session.reservation.ChargingReservation;
import pl.chargeflow.session.session.ChargingSession;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Writes outbox records within the caller's transaction — atomicity is guaranteed
 * because both the business entity and the outbox row share the same DB transaction.
 */
@Component
public class OutboxEventService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxEventService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void saveReservationCreated(ChargingReservation reservation) {
        var payload = new ReservationCreatedPayload(
                reservation.getCustomerId(),
                reservation.getCustomerEmail(),
                reservation.getChargingPoint().getId(),
                reservation.getValidUntil());
        save(reservation.getId(), OutboxAggregateType.RESERVATION,
                DomainEventType.RESERVATION_CREATED, payload);
    }

    public void saveReservationCancelled(ChargingReservation reservation) {
        var payload = new ReservationCancelledPayload(
                reservation.getCustomerId(),
                reservation.getCustomerEmail(),
                reservation.getChargingPoint().getId());
        save(reservation.getId(), OutboxAggregateType.RESERVATION,
                DomainEventType.RESERVATION_CANCELLED, payload);
    }

    public void saveReservationExpired(ChargingReservation reservation) {
        var payload = new ReservationExpiredPayload(
                reservation.getCustomerId(),
                reservation.getCustomerEmail(),
                reservation.getChargingPoint().getId());
        save(reservation.getId(), OutboxAggregateType.RESERVATION,
                DomainEventType.RESERVATION_EXPIRED, payload);
    }

    public void saveChargingStarted(ChargingSession session) {
        var payload = new ChargingStartedPayload(
                session.getCustomerId(),
                session.getReservation().getCustomerEmail(),
                session.getChargingPoint().getId(),
                session.getReservation().getId());
        save(session.getId(), OutboxAggregateType.SESSION,
                DomainEventType.CHARGING_STARTED, payload);
    }

    public void saveChargingCompleted(ChargingSession session) {
        var payload = new ChargingCompletedPayload(
                session.getCustomerId(),
                session.getReservation().getCustomerEmail(),
                session.getChargingPoint().getId(),
                session.getEnergyConsumedKwh(),
                session.getTotalCost());
        save(session.getId(), OutboxAggregateType.SESSION,
                DomainEventType.CHARGING_COMPLETED, payload);
    }

    public void saveChargingFailed(ChargingSession session) {
        var payload = new ChargingFailedPayload(
                session.getCustomerId(),
                session.getChargingPoint().getId());
        save(session.getId(), OutboxAggregateType.SESSION,
                DomainEventType.CHARGING_FAILED, payload);
    }

    private void save(UUID aggregateId, OutboxAggregateType aggregateType,
                      DomainEventType eventType, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox event payload for " + eventType, e);
        }
        repository.save(new OutboxEvent(aggregateId, aggregateType, eventType, json));
    }
}
