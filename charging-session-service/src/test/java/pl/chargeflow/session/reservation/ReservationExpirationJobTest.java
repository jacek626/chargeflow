package pl.chargeflow.session.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.chargeflow.session.station.ChargingPoint;
import pl.chargeflow.session.station.ChargingPointStatus;
import pl.chargeflow.session.station.ChargingStation;
import pl.chargeflow.session.station.ConnectorType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationExpirationJobTest {

    private ChargingReservationRepository reservationRepository;
    private ReservationExpirationJob job;

    @BeforeEach
    void setUp() {
        reservationRepository = mock(ChargingReservationRepository.class);
        job = new ReservationExpirationJob(reservationRepository,
                mock(pl.chargeflow.session.outbox.OutboxEventService.class),
                new SimpleMeterRegistry());
    }

    @Test
    void expireReservationsBefore_expiredActiveReservation_transitionsToExpired() {
        ChargingPoint point = reservedPoint();
        ChargingReservation reservation = expiredReservation(point);
        Instant now = Instant.now();

        when(reservationRepository.findActiveExpiredBefore(eq(now), any()))
                .thenReturn(List.of(reservation));

        job.expireReservationsBefore(now);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void expireReservationsBefore_expiredActiveReservation_releasesChargingPoint() {
        ChargingPoint point = reservedPoint();
        ChargingReservation reservation = expiredReservation(point);
        Instant now = Instant.now();

        when(reservationRepository.findActiveExpiredBefore(eq(now), any()))
                .thenReturn(List.of(reservation));

        job.expireReservationsBefore(now);

        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.AVAILABLE);
    }

    @Test
    void expireReservationsBefore_noExpiredReservations_doesNothing() {
        Instant now = Instant.now();
        when(reservationRepository.findActiveExpiredBefore(eq(now), any()))
                .thenReturn(List.of());

        job.expireReservationsBefore(now);
        // No exception, no interactions with reservations.
    }

    @Test
    void expireReservationsBefore_multipleExpired_expiresAll() {
        ChargingPoint point1 = reservedPoint();
        ChargingPoint point2 = reservedPoint();
        ChargingReservation r1 = expiredReservation(point1);
        ChargingReservation r2 = expiredReservation(point2);
        Instant now = Instant.now();

        when(reservationRepository.findActiveExpiredBefore(eq(now), any()))
                .thenReturn(List.of(r1, r2));

        job.expireReservationsBefore(now);

        assertThat(r1.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(r2.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(point1.getStatus()).isEqualTo(ChargingPointStatus.AVAILABLE);
        assertThat(point2.getStatus()).isEqualTo(ChargingPointStatus.AVAILABLE);
    }

    @Test
    void expireReservationsBefore_passesActiveStatusesToRepository() {
        Instant now = Instant.now();
        when(reservationRepository.findActiveExpiredBefore(any(), any())).thenReturn(List.of());

        job.expireReservationsBefore(now);

        verify(reservationRepository).findActiveExpiredBefore(
                eq(now),
                eq(List.of(ReservationStatus.CREATED, ReservationStatus.CONFIRMED)));
    }

    // --- helpers ---

    private ChargingPoint reservedPoint() {
        ChargingStation station = new ChargingStation("Test", "ul. X", "Lublin",
                BigDecimal.valueOf(51.0), BigDecimal.valueOf(22.0), true);
        ChargingPoint point = new ChargingPoint(station, "TST-01", ConnectorType.CCS,
                BigDecimal.valueOf(150), BigDecimal.valueOf(2.49),
                ChargingPointStatus.RESERVED, true);
        ChargingPoint spied = spy(point);
        doReturn(UUID.randomUUID()).when(spied).getId();
        return spied;
    }

    private ChargingReservation expiredReservation(ChargingPoint point) {
        Instant past = Instant.now().minus(30, ChronoUnit.MINUTES);
        return new ChargingReservation("cust-1", "cust@example.com", point, past, past.plus(15, ChronoUnit.MINUTES));
    }
}
