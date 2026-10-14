package pl.chargeflow.session.reservation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.chargeflow.session.outbox.OutboxEventService;
import pl.chargeflow.session.reservation.api.ReservationResponse;
import pl.chargeflow.session.reservation.exception.ReservationAccessDeniedException;
import pl.chargeflow.session.reservation.exception.ReservationNotFoundException;
import pl.chargeflow.session.station.ChargingPoint;
import pl.chargeflow.session.station.ChargingPointRepository;
import pl.chargeflow.session.station.exception.ChargingPointNotFoundException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ReservationService {

    private final ChargingReservationRepository reservationRepository;
    private final ChargingPointRepository pointRepository;
    private final ReservationMapper mapper;
    private final OutboxEventService outboxEventService;
    private final MeterRegistry meterRegistry;

    public ReservationService(ChargingReservationRepository reservationRepository,
                               ChargingPointRepository pointRepository,
                               ReservationMapper mapper,
                               OutboxEventService outboxEventService,
                               MeterRegistry meterRegistry) {
        this.reservationRepository = reservationRepository;
        this.pointRepository = pointRepository;
        this.mapper = mapper;
        this.outboxEventService = outboxEventService;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ReservationResponse createReservation(UUID chargingPointId, String customerId, String customerEmail) {
        ChargingPoint point = pointRepository.findById(chargingPointId)
                .orElseThrow(() -> new ChargingPointNotFoundException(chargingPointId));

        point.reserve(); // AVAILABLE → RESERVED; throws ChargingPointNotAvailableException if not available

        Instant now = Instant.now();
        ChargingReservation reservation = new ChargingReservation(
                customerId, customerEmail, point, now, now.plus(15, ChronoUnit.MINUTES));

        ChargingReservation saved = reservationRepository.save(reservation);
        outboxEventService.saveReservationCreated(saved);
        meterRegistry.counter("chargeflow.reservations.created").increment();
        return mapper.toResponse(saved);
    }

    public List<ReservationResponse> listReservations(String customerId) {
        return reservationRepository.findByCustomerIdOrderByReservedAtDesc(customerId)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    public List<ReservationResponse> listAllReservations() {
        return reservationRepository.findAllByOrderByReservedAtDesc()
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    public ReservationResponse getReservation(UUID reservationId, String customerId) {
        ChargingReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (!reservation.getCustomerId().equals(customerId)) {
            throw new ReservationAccessDeniedException(reservationId);
        }
        return mapper.toResponse(reservation);
    }

    @Transactional
    public void cancelReservation(UUID reservationId, String customerId) {
        ChargingReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (!reservation.getCustomerId().equals(customerId)) {
            throw new ReservationAccessDeniedException(reservationId);
        }
        reservation.cancel(); // throws InvalidReservationStatusTransitionException if not cancellable

        ChargingPoint point = reservation.getChargingPoint();
        point.releaseReservation(); // RESERVED → AVAILABLE

        outboxEventService.saveReservationCancelled(reservation);
        meterRegistry.counter("chargeflow.reservations.cancelled").increment();
    }
}
