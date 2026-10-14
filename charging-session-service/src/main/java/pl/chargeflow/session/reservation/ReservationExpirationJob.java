package pl.chargeflow.session.reservation;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pl.chargeflow.session.outbox.OutboxEventService;

import java.time.Instant;
import java.util.List;

@Component
public class ReservationExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationJob.class);

    private static final List<ReservationStatus> ACTIVE_STATUSES =
            List.of(ReservationStatus.CREATED, ReservationStatus.CONFIRMED);

    private final ChargingReservationRepository reservationRepository;
    private final OutboxEventService outboxEventService;
    private final MeterRegistry meterRegistry;

    public ReservationExpirationJob(ChargingReservationRepository reservationRepository,
                                     OutboxEventService outboxEventService,
                                     MeterRegistry meterRegistry) {
        this.reservationRepository = reservationRepository;
        this.outboxEventService = outboxEventService;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${chargeflow.reservation.expiration.poll-interval-ms:60000}")
    public void expireReservations() {
        expireReservationsBefore(Instant.now());
    }

    // Package-visible for testing — allows injecting a fixed reference time.
    @Transactional
    void expireReservationsBefore(Instant now) {
        List<ChargingReservation> expired =
                reservationRepository.findActiveExpiredBefore(now, ACTIVE_STATUSES);

        if (expired.isEmpty()) {
            return;
        }

        for (ChargingReservation reservation : expired) {
            reservation.expire();
            reservation.getChargingPoint().releaseReservation();
            outboxEventService.saveReservationExpired(reservation);
        }

        meterRegistry.counter("chargeflow.reservations.expired").increment(expired.size());
        log.info("Expired {} reservation(s)", expired.size());
    }
}
