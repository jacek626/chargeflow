package pl.chargeflow.session.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.chargeflow.session.reservation.api.ReservationResponse;
import pl.chargeflow.session.reservation.exception.ReservationAccessDeniedException;
import pl.chargeflow.session.reservation.exception.ReservationNotFoundException;
import pl.chargeflow.session.station.ChargingPoint;
import pl.chargeflow.session.station.ChargingPointRepository;
import pl.chargeflow.session.station.ChargingPointStatus;
import pl.chargeflow.session.station.ChargingStation;
import pl.chargeflow.session.station.ConnectorType;
import pl.chargeflow.session.station.exception.ChargingPointNotFoundException;
import pl.chargeflow.session.station.exception.ChargingPointNotAvailableException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class ReservationServiceTest {

    private ChargingReservationRepository reservationRepository;
    private ChargingPointRepository pointRepository;
    private ReservationService service;

    @BeforeEach
    void setUp() {
        reservationRepository = mock(ChargingReservationRepository.class);
        pointRepository = mock(ChargingPointRepository.class);
        service = new ReservationService(reservationRepository, pointRepository, new ReservationMapper(),
                mock(pl.chargeflow.session.outbox.OutboxEventService.class),
                new SimpleMeterRegistry());
    }

    // --- createReservation ---

    @Test
    void createReservation_availablePoint_returnsCreatedReservation() {
        UUID pointId = UUID.randomUUID();
        ChargingPoint point = availablePoint(pointId);

        when(pointRepository.findById(pointId)).thenReturn(Optional.of(point));
        when(reservationRepository.save(any())).thenAnswer(inv ->
                persistedReservation(UUID.randomUUID(), inv.getArgument(0)));

        ReservationResponse response = service.createReservation(pointId, "cust-1", "cust@example.com");

        assertThat(response.customerId()).isEqualTo("cust-1");
        assertThat(response.chargingPointId()).isEqualTo(pointId);
        assertThat(response.status()).isEqualTo(ReservationStatus.CREATED);
        assertThat(response.id()).isNotNull();
    }

    @Test
    void createReservation_validUntilIs15MinutesAfterReservedAt() {
        UUID pointId = UUID.randomUUID();
        ChargingPoint point = availablePoint(pointId);

        when(pointRepository.findById(pointId)).thenReturn(Optional.of(point));
        when(reservationRepository.save(any())).thenAnswer(inv ->
                persistedReservation(UUID.randomUUID(), inv.getArgument(0)));

        Instant before = Instant.now();
        ReservationResponse response = service.createReservation(pointId, "cust-1", "cust@example.com");
        Instant after = Instant.now();

        assertThat(response.validUntil())
                .isAfterOrEqualTo(before.plus(15, ChronoUnit.MINUTES))
                .isBeforeOrEqualTo(after.plus(15, ChronoUnit.MINUTES));
    }

    @Test
    void createReservation_setsChargingPointToReserved() {
        UUID pointId = UUID.randomUUID();
        ChargingPoint point = availablePoint(pointId);

        when(pointRepository.findById(pointId)).thenReturn(Optional.of(point));
        when(reservationRepository.save(any())).thenAnswer(inv ->
                persistedReservation(UUID.randomUUID(), inv.getArgument(0)));

        service.createReservation(pointId, "cust-1", "cust@example.com");

        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.RESERVED);
    }

    @Test
    void createReservation_pointNotFound_throwsChargingPointNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(pointRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createReservation(unknownId, "cust-1", "cust@example.com"))
                .isInstanceOf(ChargingPointNotFoundException.class);
    }

    @Test
    void createReservation_pointNotAvailable_throwsChargingPointNotAvailable() {
        UUID pointId = UUID.randomUUID();
        ChargingPoint point = pointWithStatus(pointId, ChargingPointStatus.OUT_OF_SERVICE);
        when(pointRepository.findById(pointId)).thenReturn(Optional.of(point));

        assertThatThrownBy(() -> service.createReservation(pointId, "cust-1", "cust@example.com"))
                .isInstanceOf(ChargingPointNotAvailableException.class);
    }

    @Test
    void createReservation_pointAlreadyReserved_throwsChargingPointNotAvailable() {
        UUID pointId = UUID.randomUUID();
        ChargingPoint point = pointWithStatus(pointId, ChargingPointStatus.RESERVED);
        when(pointRepository.findById(pointId)).thenReturn(Optional.of(point));

        assertThatThrownBy(() -> service.createReservation(pointId, "cust-1", "cust@example.com"))
                .isInstanceOf(ChargingPointNotAvailableException.class);
    }

    // --- listReservations ---

    @Test
    void listReservations_returnsAllMappedReservations() {
        UUID pointId = UUID.randomUUID();
        ChargingPoint point = availablePoint(pointId);
        ChargingReservation r = persistedReservation(UUID.randomUUID(), reservationFor("cust-1", point));

        when(reservationRepository.findByCustomerIdOrderByReservedAtDesc("cust-1"))
                .thenReturn(List.of(r));

        List<ReservationResponse> result = service.listReservations("cust-1");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().customerId()).isEqualTo("cust-1");
    }

    @Test
    void listReservations_noReservations_returnsEmptyList() {
        when(reservationRepository.findByCustomerIdOrderByReservedAtDesc("cust-1"))
                .thenReturn(List.of());

        assertThat(service.listReservations("cust-1")).isEmpty();
    }

    // --- getReservation ---

    @Test
    void getReservation_ownReservation_returnsResponse() {
        UUID reservationId = UUID.randomUUID();
        UUID pointId = UUID.randomUUID();
        ChargingReservation r = persistedReservation(reservationId,
                reservationFor("cust-1", availablePoint(pointId)));

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));

        ReservationResponse response = service.getReservation(reservationId, "cust-1");

        assertThat(response.id()).isEqualTo(reservationId);
        assertThat(response.customerId()).isEqualTo("cust-1");
    }

    @Test
    void getReservation_notFound_throwsReservationNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(reservationRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getReservation(unknownId, "cust-1"))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void getReservation_differentCustomer_throwsAccessDenied() {
        UUID reservationId = UUID.randomUUID();
        ChargingReservation r = persistedReservation(reservationId,
                reservationFor("cust-1", availablePoint(UUID.randomUUID())));

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.getReservation(reservationId, "cust-2"))
                .isInstanceOf(ReservationAccessDeniedException.class);
    }

    // --- cancelReservation ---

    @Test
    void cancelReservation_ownActiveReservation_cancelsAndReleasesPoint() {
        UUID reservationId = UUID.randomUUID();
        ChargingPoint point = pointWithStatus(UUID.randomUUID(), ChargingPointStatus.RESERVED);
        ChargingReservation r = persistedReservation(reservationId, reservationFor("cust-1", point));

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));

        service.cancelReservation(reservationId, "cust-1");

        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.AVAILABLE);
    }

    @Test
    void cancelReservation_notFound_throwsReservationNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(reservationRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelReservation(unknownId, "cust-1"))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void cancelReservation_differentCustomer_throwsAccessDenied() {
        UUID reservationId = UUID.randomUUID();
        ChargingReservation r = persistedReservation(reservationId,
                reservationFor("cust-1", availablePoint(UUID.randomUUID())));

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.cancelReservation(reservationId, "cust-2"))
                .isInstanceOf(ReservationAccessDeniedException.class);
    }

    // --- helpers ---

    private ChargingPoint availablePoint(UUID id) {
        return pointWithStatus(id, ChargingPointStatus.AVAILABLE);
    }

    private ChargingPoint pointWithStatus(UUID id, ChargingPointStatus status) {
        ChargingStation station = new ChargingStation("Test", "ul. X", "Lublin",
                BigDecimal.valueOf(51.0), BigDecimal.valueOf(22.0), true);
        ChargingPoint point = new ChargingPoint(station, "TST-01", ConnectorType.CCS,
                BigDecimal.valueOf(150), BigDecimal.valueOf(2.49), status, true);
        ChargingPoint spied = spy(point);
        doReturn(id).when(spied).getId();
        return spied;
    }

    private ChargingReservation reservationFor(String customerId, ChargingPoint point) {
        Instant now = Instant.now();
        return new ChargingReservation(customerId, "cust@example.com", point, now, now.plus(15, ChronoUnit.MINUTES));
    }

    private ChargingReservation persistedReservation(UUID id, ChargingReservation r) {
        ChargingReservation spied = spy(r);
        doReturn(id).when(spied).getId();
        return spied;
    }
}
