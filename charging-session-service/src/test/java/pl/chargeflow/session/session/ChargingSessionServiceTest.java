package pl.chargeflow.session.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.chargeflow.session.reservation.ChargingReservation;
import pl.chargeflow.session.reservation.ChargingReservationRepository;
import pl.chargeflow.session.reservation.ReservationStatus;
import pl.chargeflow.session.reservation.exception.ReservationAccessDeniedException;
import pl.chargeflow.session.reservation.exception.ReservationExpiredException;
import pl.chargeflow.session.reservation.exception.ReservationNotFoundException;
import pl.chargeflow.session.session.api.ChargingSessionResponse;
import pl.chargeflow.session.session.exception.ChargingSessionAccessDeniedException;
import pl.chargeflow.session.session.exception.ChargingSessionNotFoundException;
import pl.chargeflow.session.station.ChargingPoint;
import pl.chargeflow.session.station.ChargingPointStatus;
import pl.chargeflow.session.station.ChargingStation;
import pl.chargeflow.session.station.ConnectorType;
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

class ChargingSessionServiceTest {

    private ChargingSessionRepository sessionRepository;
    private ChargingReservationRepository reservationRepository;
    private ChargingSessionService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChargingSessionRepository.class);
        reservationRepository = mock(ChargingReservationRepository.class);
        service = new ChargingSessionService(sessionRepository, reservationRepository,
                new ChargingSessionMapper(),
                mock(pl.chargeflow.session.outbox.OutboxEventService.class),
                new SimpleMeterRegistry());
    }

    // --- startSession ---

    @Test
    void startSession_happyPath_returnsStartedSession() {
        UUID reservationId = UUID.randomUUID();
        ChargingPoint point = reservedPoint(UUID.randomUUID());
        ChargingReservation reservation = persistedReservation(UUID.randomUUID(),
                reservationFor("cust-1", point));

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(sessionRepository.save(any())).thenAnswer(inv ->
                persistedSession(UUID.randomUUID(), inv.getArgument(0)));

        ChargingSessionResponse response = service.startSession(reservationId, "cust-1");

        assertThat(response.customerId()).isEqualTo("cust-1");
        assertThat(response.status()).isEqualTo(SessionStatus.STARTED);
        assertThat(response.id()).isNotNull();
    }

    @Test
    void startSession_setsReservationToUsedAndPointToCharging() {
        UUID reservationId = UUID.randomUUID();
        ChargingPoint point = reservedPoint(UUID.randomUUID());
        ChargingReservation reservation = persistedReservation(UUID.randomUUID(),
                reservationFor("cust-1", point));

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(sessionRepository.save(any())).thenAnswer(inv ->
                persistedSession(UUID.randomUUID(), inv.getArgument(0)));

        service.startSession(reservationId, "cust-1");

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.USED);
        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.CHARGING);
    }

    @Test
    void startSession_reservationNotFound_throwsReservationNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(reservationRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startSession(unknownId, "cust-1"))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void startSession_wrongCustomer_throwsAccessDenied() {
        UUID reservationId = UUID.randomUUID();
        ChargingReservation reservation = persistedReservation(UUID.randomUUID(),
                reservationFor("cust-1", reservedPoint(UUID.randomUUID())));

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.startSession(reservationId, "cust-2"))
                .isInstanceOf(ReservationAccessDeniedException.class);
    }

    @Test
    void startSession_expiredReservation_throwsReservationExpired() {
        UUID reservationId = UUID.randomUUID();
        ChargingReservation reservation = persistedReservation(UUID.randomUUID(),
                reservationWithStatus("cust-1", reservedPoint(UUID.randomUUID()), ReservationStatus.EXPIRED));

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.startSession(reservationId, "cust-1"))
                .isInstanceOf(ReservationExpiredException.class);
    }

    @Test
    void startSession_pointNotReserved_throwsChargingPointNotAvailable() {
        UUID reservationId = UUID.randomUUID();
        ChargingPoint point = pointWithStatus(UUID.randomUUID(), ChargingPointStatus.AVAILABLE);
        ChargingReservation reservation = persistedReservation(UUID.randomUUID(),
                reservationFor("cust-1", point));

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.startSession(reservationId, "cust-1"))
                .isInstanceOf(ChargingPointNotAvailableException.class);
    }

    // --- stopSession ---

    @Test
    void stopSession_happyPath_returnsCompletedSession() {
        UUID sessionId = UUID.randomUUID();
        ChargingSession session = persistedSession(sessionId, activeSession("cust-1"));

        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        ChargingSessionResponse response = service.stopSession(sessionId, "cust-1");

        assertThat(response.status()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(response.stoppedAt()).isNotNull();
    }

    @Test
    void stopSession_setsPointToAvailable() {
        UUID sessionId = UUID.randomUUID();
        ChargingSession session = persistedSession(sessionId, activeSession("cust-1"));
        ChargingPoint point = session.getChargingPoint();

        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        service.stopSession(sessionId, "cust-1");

        assertThat(point.getStatus()).isEqualTo(ChargingPointStatus.AVAILABLE);
    }

    @Test
    void stopSession_notFound_throwsSessionNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(sessionRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.stopSession(unknownId, "cust-1"))
                .isInstanceOf(ChargingSessionNotFoundException.class);
    }

    @Test
    void stopSession_wrongCustomer_throwsAccessDenied() {
        UUID sessionId = UUID.randomUUID();
        ChargingSession session = persistedSession(sessionId, activeSession("cust-1"));

        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.stopSession(sessionId, "cust-2"))
                .isInstanceOf(ChargingSessionAccessDeniedException.class);
    }

    // --- listSessions ---

    @Test
    void listSessions_returnsMappedResponses() {
        ChargingSession s = persistedSession(UUID.randomUUID(), activeSession("cust-1"));
        when(sessionRepository.findByCustomerIdOrderByStartedAtDesc("cust-1"))
                .thenReturn(List.of(s));

        List<ChargingSessionResponse> result = service.listSessions("cust-1");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().customerId()).isEqualTo("cust-1");
    }

    @Test
    void listSessions_noSessions_returnsEmptyList() {
        when(sessionRepository.findByCustomerIdOrderByStartedAtDesc("cust-1"))
                .thenReturn(List.of());

        assertThat(service.listSessions("cust-1")).isEmpty();
    }

    // --- getSession ---

    @Test
    void getSession_ownSession_returnsResponse() {
        UUID sessionId = UUID.randomUUID();
        ChargingSession s = persistedSession(sessionId, activeSession("cust-1"));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(s));

        ChargingSessionResponse response = service.getSession(sessionId, "cust-1");

        assertThat(response.id()).isEqualTo(sessionId);
        assertThat(response.customerId()).isEqualTo("cust-1");
    }

    @Test
    void getSession_notFound_throwsSessionNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(sessionRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSession(unknownId, "cust-1"))
                .isInstanceOf(ChargingSessionNotFoundException.class);
    }

    @Test
    void getSession_wrongCustomer_throwsAccessDenied() {
        UUID sessionId = UUID.randomUUID();
        ChargingSession s = persistedSession(sessionId, activeSession("cust-1"));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.getSession(sessionId, "cust-2"))
                .isInstanceOf(ChargingSessionAccessDeniedException.class);
    }

    // --- helpers ---

    private ChargingPoint reservedPoint(UUID id) {
        return pointWithStatus(id, ChargingPointStatus.RESERVED);
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

    private ChargingReservation reservationWithStatus(String customerId, ChargingPoint point,
                                                       ReservationStatus status) {
        ChargingReservation r = reservationFor(customerId, point);
        try {
            java.lang.reflect.Field f = ChargingReservation.class.getDeclaredField("status");
            f.setAccessible(true);
            f.set(r, status);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Test setup failed", e);
        }
        return r;
    }

    private ChargingReservation persistedReservation(UUID id, ChargingReservation r) {
        ChargingReservation spied = spy(r);
        doReturn(id).when(spied).getId();
        return spied;
    }

    private ChargingSession activeSession(String customerId) {
        ChargingPoint point = pointWithStatus(UUID.randomUUID(), ChargingPointStatus.CHARGING);
        Instant now = Instant.now();
        ChargingReservation reservation = persistedReservation(UUID.randomUUID(),
                reservationWithStatus(customerId, point, ReservationStatus.USED));
        return new ChargingSession(customerId, point, reservation, now);
    }

    private ChargingSession persistedSession(UUID id, ChargingSession s) {
        ChargingSession spied = spy(s);
        doReturn(id).when(spied).getId();
        return spied;
    }
}
