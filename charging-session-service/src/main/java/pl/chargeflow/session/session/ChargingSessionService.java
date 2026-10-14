package pl.chargeflow.session.session;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.chargeflow.session.outbox.OutboxEventService;
import pl.chargeflow.session.reservation.ChargingReservation;
import pl.chargeflow.session.reservation.ChargingReservationRepository;
import pl.chargeflow.session.reservation.ReservationStatus;
import pl.chargeflow.session.reservation.exception.ReservationExpiredException;
import pl.chargeflow.session.reservation.exception.ReservationNotFoundException;
import pl.chargeflow.session.session.api.ChargingSessionResponse;
import pl.chargeflow.session.session.exception.ChargingSessionAccessDeniedException;
import pl.chargeflow.session.session.exception.ChargingSessionNotFoundException;
import pl.chargeflow.session.station.ChargingPoint;
import pl.chargeflow.session.station.ChargingPointStatus;
import pl.chargeflow.session.station.exception.ChargingPointNotAvailableException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ChargingSessionService {

    private static final Random RANDOM = new Random();

    private final ChargingSessionRepository sessionRepository;
    private final ChargingReservationRepository reservationRepository;
    private final ChargingSessionMapper mapper;
    private final OutboxEventService outboxEventService;
    private final MeterRegistry meterRegistry;

    public ChargingSessionService(ChargingSessionRepository sessionRepository,
                                  ChargingReservationRepository reservationRepository,
                                  ChargingSessionMapper mapper,
                                  OutboxEventService outboxEventService,
                                  MeterRegistry meterRegistry) {
        this.sessionRepository = sessionRepository;
        this.reservationRepository = reservationRepository;
        this.mapper = mapper;
        this.outboxEventService = outboxEventService;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ChargingSessionResponse startSession(UUID reservationId, String customerId) {
        ChargingReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (!reservation.getCustomerId().equals(customerId)) {
            throw new pl.chargeflow.session.reservation.exception.ReservationAccessDeniedException(reservationId);
        }

        if (reservation.getStatus() == ReservationStatus.EXPIRED) {
            throw new ReservationExpiredException(reservationId);
        }

        if (!reservation.isActive()) {
            throw new pl.chargeflow.session.reservation.exception.InvalidReservationStatusTransitionException(
                    reservationId, reservation.getStatus(), ReservationStatus.USED);
        }

        ChargingPoint point = reservation.getChargingPoint();
        if (point.getStatus() != ChargingPointStatus.RESERVED) {
            throw new ChargingPointNotAvailableException(point.getId());
        }

        reservation.markUsed();
        point.startCharging();

        ChargingSession session = new ChargingSession(customerId, point, reservation, Instant.now());
        ChargingSession saved = sessionRepository.save(session);
        outboxEventService.saveChargingStarted(saved);
        meterRegistry.counter("chargeflow.sessions.started").increment();
        return mapper.toResponse(saved);
    }

    @Transactional
    public ChargingSessionResponse stopSession(UUID sessionId, String customerId) {
        ChargingSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ChargingSessionNotFoundException(sessionId));

        if (!session.getCustomerId().equals(customerId)) {
            throw new ChargingSessionAccessDeniedException(sessionId);
        }

        BigDecimal energyKwh = simulateEnergyConsumed();
        session.complete(energyKwh);
        session.getChargingPoint().stopCharging();
        outboxEventService.saveChargingCompleted(session);
        meterRegistry.counter("chargeflow.sessions.completed").increment();
        return mapper.toResponse(session);
    }

    public List<ChargingSessionResponse> listSessions(String customerId) {
        return sessionRepository.findByCustomerIdOrderByStartedAtDesc(customerId)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    public List<ChargingSessionResponse> listAllSessions() {
        return sessionRepository.findAllByOrderByStartedAtDesc()
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    public ChargingSessionResponse getSession(UUID sessionId, String customerId) {
        ChargingSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ChargingSessionNotFoundException(sessionId));

        if (!session.getCustomerId().equals(customerId)) {
            throw new ChargingSessionAccessDeniedException(sessionId);
        }

        return mapper.toResponse(session);
    }

    // V1 simulation: random energy between 5.0 and 60.0 kWh, 4 decimal precision.
    private BigDecimal simulateEnergyConsumed() {
        double kwh = 5.0 + RANDOM.nextDouble() * 55.0;
        return BigDecimal.valueOf(kwh).setScale(4, RoundingMode.HALF_UP);
    }
}
