package pl.chargeflow.session.session;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.chargeflow.session.config.SecurityConfig;
import pl.chargeflow.session.reservation.exception.ReservationExpiredException;
import pl.chargeflow.session.reservation.exception.ReservationNotFoundException;
import pl.chargeflow.session.session.api.ChargingSessionResponse;
import pl.chargeflow.session.session.exception.ChargingSessionAccessDeniedException;
import pl.chargeflow.session.session.exception.ChargingSessionNotFoundException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = ChargingSessionController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class,
        OAuth2ResourceServerWebSecurityAutoConfiguration.class
    }
)
@Import(SecurityConfig.class)
class ChargingSessionControllerTest {

    @MockitoBean
    ChargingSessionService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mockMvc;

    private static final UUID   RESERVATION_ID = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID   SESSION_ID      = UUID.fromString("22222222-0000-0000-0000-000000000001");
    private static final UUID   POINT_ID        = UUID.fromString("33333333-0000-0000-0000-000000000001");
    private static final String CUSTOMER_ID     = "customer-abc";

    // --- POST /api/charging-sessions ---

    @Test
    void startSession_validRequest_returns201WithSessionBody() throws Exception {
        ChargingSessionResponse response = sessionResponse(SESSION_ID, CUSTOMER_ID, SessionStatus.STARTED);
        when(service.startSession(RESERVATION_ID, CUSTOMER_ID)).thenReturn(response);

        mockMvc.perform(post("/api/charging-sessions")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationId\":\"" + RESERVATION_ID + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(SESSION_ID.toString())))
                .andExpect(jsonPath("$.customerId", is(CUSTOMER_ID)))
                .andExpect(jsonPath("$.status", is("STARTED")));
    }

    @Test
    void startSession_missingReservationId_returns400() throws Exception {
        mockMvc.perform(post("/api/charging-sessions")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_REQUEST")));
    }

    @Test
    void startSession_reservationNotFound_returns404() throws Exception {
        when(service.startSession(any(), any()))
                .thenThrow(new ReservationNotFoundException(RESERVATION_ID));

        mockMvc.perform(post("/api/charging-sessions")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationId\":\"" + RESERVATION_ID + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("RESERVATION_NOT_FOUND")));
    }

    @Test
    void startSession_expiredReservation_returns409() throws Exception {
        when(service.startSession(any(), any()))
                .thenThrow(new ReservationExpiredException(RESERVATION_ID));

        mockMvc.perform(post("/api/charging-sessions")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationId\":\"" + RESERVATION_ID + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RESERVATION_EXPIRED")));
    }

    @Test
    void startSession_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/charging-sessions")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startSession_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(post("/api/charging-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationId\":\"" + RESERVATION_ID + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- POST /api/charging-sessions/{id}/stop ---

    @Test
    void stopSession_activeSession_returns200WithCompletedSession() throws Exception {
        ChargingSessionResponse response = sessionResponse(SESSION_ID, CUSTOMER_ID, SessionStatus.COMPLETED);
        when(service.stopSession(SESSION_ID, CUSTOMER_ID)).thenReturn(response);

        mockMvc.perform(post("/api/charging-sessions/{id}/stop", SESSION_ID)
                        .with(customerJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(SESSION_ID.toString())))
                .andExpect(jsonPath("$.status", is("COMPLETED")));
    }

    @Test
    void stopSession_notFound_returns404() throws Exception {
        when(service.stopSession(eq(SESSION_ID), any()))
                .thenThrow(new ChargingSessionNotFoundException(SESSION_ID));

        mockMvc.perform(post("/api/charging-sessions/{id}/stop", SESSION_ID)
                        .with(customerJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("CHARGING_SESSION_NOT_FOUND")));
    }

    @Test
    void stopSession_wrongCustomer_returns403() throws Exception {
        when(service.stopSession(eq(SESSION_ID), any()))
                .thenThrow(new ChargingSessionAccessDeniedException(SESSION_ID));

        mockMvc.perform(post("/api/charging-sessions/{id}/stop", SESSION_ID)
                        .with(customerJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("CHARGING_SESSION_ACCESS_DENIED")));
    }

    // --- GET /api/charging-sessions ---

    @Test
    void listSessions_returnsAllSessionsForCustomer() throws Exception {
        List<ChargingSessionResponse> list = List.of(
                sessionResponse(SESSION_ID, CUSTOMER_ID, SessionStatus.STARTED),
                sessionResponse(UUID.randomUUID(), CUSTOMER_ID, SessionStatus.COMPLETED)
        );
        when(service.listSessions(CUSTOMER_ID)).thenReturn(list);

        mockMvc.perform(get("/api/charging-sessions")
                        .with(customerJwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id", is(SESSION_ID.toString())))
                .andExpect(jsonPath("$[0].status", is("STARTED")))
                .andExpect(jsonPath("$[1].status", is("COMPLETED")));
    }

    @Test
    void listSessions_emptyList_returns200WithEmptyArray() throws Exception {
        when(service.listSessions(CUSTOMER_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/charging-sessions")
                        .with(customerJwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // --- GET /api/charging-sessions/{id} ---

    @Test
    void getSession_existingOwnSession_returns200() throws Exception {
        ChargingSessionResponse response = sessionResponse(SESSION_ID, CUSTOMER_ID, SessionStatus.STARTED);
        when(service.getSession(SESSION_ID, CUSTOMER_ID)).thenReturn(response);

        mockMvc.perform(get("/api/charging-sessions/{id}", SESSION_ID)
                        .with(customerJwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(SESSION_ID.toString())))
                .andExpect(jsonPath("$.status", is("STARTED")));
    }

    @Test
    void getSession_notFound_returns404() throws Exception {
        when(service.getSession(eq(SESSION_ID), any()))
                .thenThrow(new ChargingSessionNotFoundException(SESSION_ID));

        mockMvc.perform(get("/api/charging-sessions/{id}", SESSION_ID)
                        .with(customerJwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("CHARGING_SESSION_NOT_FOUND")));
    }

    @Test
    void getSession_wrongCustomer_returns403() throws Exception {
        when(service.getSession(eq(SESSION_ID), any()))
                .thenThrow(new ChargingSessionAccessDeniedException(SESSION_ID));

        mockMvc.perform(get("/api/charging-sessions/{id}", SESSION_ID)
                        .with(customerJwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("CHARGING_SESSION_ACCESS_DENIED")));
    }

    // --- helpers ---

    private org.springframework.test.web.servlet.request.RequestPostProcessor customerJwt() {
        return jwt()
                .jwt(j -> j.subject(CUSTOMER_ID).claim("cognito:groups", List.of("CUSTOMER")))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private ChargingSessionResponse sessionResponse(UUID id, String customerId, SessionStatus status) {
        Instant now = Instant.now();
        return new ChargingSessionResponse(id, customerId, POINT_ID, RESERVATION_ID,
                status, now, null, null, BigDecimal.valueOf(2.49), null, now, now);
    }
}
