package pl.chargeflow.session.reservation;

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
import pl.chargeflow.session.reservation.api.ReservationResponse;
import pl.chargeflow.session.reservation.exception.ReservationAccessDeniedException;
import pl.chargeflow.session.reservation.exception.ReservationNotFoundException;
import pl.chargeflow.session.station.exception.ChargingPointNotFoundException;
import pl.chargeflow.session.station.exception.ChargingPointNotAvailableException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = ReservationController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class,
        OAuth2ResourceServerWebSecurityAutoConfiguration.class
    }
)
@Import(SecurityConfig.class)
class ReservationControllerTest {

    @MockitoBean
    ReservationService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mockMvc;

    private static final UUID POINT_ID        = UUID.fromString("22222222-0000-0000-0000-000000000001");
    private static final UUID RESERVATION_ID  = UUID.fromString("33333333-0000-0000-0000-000000000001");
    private static final String CUSTOMER_ID   = "customer-abc";
    private static final String CUSTOMER_EMAIL = "customer@example.com";

    // --- POST /api/reservations ---

    @Test
    void createReservation_validRequest_returns201WithReservationBody() throws Exception {
        ReservationResponse response = reservationResponse(RESERVATION_ID, POINT_ID, CUSTOMER_ID, ReservationStatus.CREATED);
        when(service.createReservation(POINT_ID, CUSTOMER_ID, CUSTOMER_EMAIL)).thenReturn(response);

        mockMvc.perform(post("/api/reservations")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chargingPointId\":\"" + POINT_ID + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(RESERVATION_ID.toString())))
                .andExpect(jsonPath("$.customerId", is(CUSTOMER_ID)))
                .andExpect(jsonPath("$.chargingPointId", is(POINT_ID.toString())))
                .andExpect(jsonPath("$.status", is("CREATED")));
    }

    @Test
    void createReservation_missingChargingPointId_returns400() throws Exception {
        mockMvc.perform(post("/api/reservations")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_REQUEST")));
    }

    @Test
    void createReservation_pointNotFound_returns404() throws Exception {
        when(service.createReservation(any(), any(), any()))
                .thenThrow(new ChargingPointNotFoundException(POINT_ID));

        mockMvc.perform(post("/api/reservations")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chargingPointId\":\"" + POINT_ID + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("CHARGING_POINT_NOT_FOUND")));
    }

    @Test
    void createReservation_pointNotAvailable_returns409() throws Exception {
        when(service.createReservation(any(), any(), any()))
                .thenThrow(new ChargingPointNotAvailableException(POINT_ID));

        mockMvc.perform(post("/api/reservations")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chargingPointId\":\"" + POINT_ID + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CHARGING_POINT_NOT_AVAILABLE")));
    }

    @Test
    void createReservation_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/reservations")
                        .with(customerJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReservation_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chargingPointId\":\"" + POINT_ID + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- GET /api/reservations ---

    @Test
    void listReservations_returnsAllReservationsForCustomer() throws Exception {
        List<ReservationResponse> list = List.of(
                reservationResponse(RESERVATION_ID, POINT_ID, CUSTOMER_ID, ReservationStatus.CREATED),
                reservationResponse(UUID.randomUUID(), UUID.randomUUID(), CUSTOMER_ID, ReservationStatus.CANCELLED)
        );
        when(service.listReservations(CUSTOMER_ID)).thenReturn(list);

        mockMvc.perform(get("/api/reservations")
                        .with(customerJwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id", is(RESERVATION_ID.toString())))
                .andExpect(jsonPath("$[0].status", is("CREATED")))
                .andExpect(jsonPath("$[1].status", is("CANCELLED")));
    }

    @Test
    void listReservations_emptyList_returns200WithEmptyArray() throws Exception {
        when(service.listReservations(CUSTOMER_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/reservations")
                        .with(customerJwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // --- GET /api/reservations/{reservationId} ---

    @Test
    void getReservation_existingOwnReservation_returns200() throws Exception {
        ReservationResponse response = reservationResponse(RESERVATION_ID, POINT_ID, CUSTOMER_ID, ReservationStatus.CREATED);
        when(service.getReservation(RESERVATION_ID, CUSTOMER_ID)).thenReturn(response);

        mockMvc.perform(get("/api/reservations/{id}", RESERVATION_ID)
                        .with(customerJwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(RESERVATION_ID.toString())))
                .andExpect(jsonPath("$.status", is("CREATED")));
    }

    @Test
    void getReservation_notFound_returns404() throws Exception {
        when(service.getReservation(eq(RESERVATION_ID), any()))
                .thenThrow(new ReservationNotFoundException(RESERVATION_ID));

        mockMvc.perform(get("/api/reservations/{id}", RESERVATION_ID)
                        .with(customerJwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("RESERVATION_NOT_FOUND")));
    }

    @Test
    void getReservation_wrongCustomer_returns403() throws Exception {
        when(service.getReservation(eq(RESERVATION_ID), any()))
                .thenThrow(new ReservationAccessDeniedException(RESERVATION_ID));

        mockMvc.perform(get("/api/reservations/{id}", RESERVATION_ID)
                        .with(customerJwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("RESERVATION_ACCESS_DENIED")));
    }

    // --- DELETE /api/reservations/{reservationId} ---

    @Test
    void cancelReservation_activeOwnReservation_returns204() throws Exception {
        doNothing().when(service).cancelReservation(RESERVATION_ID, CUSTOMER_ID);

        mockMvc.perform(delete("/api/reservations/{id}", RESERVATION_ID)
                        .with(customerJwt()))
                .andExpect(status().isNoContent());
    }

    @Test
    void cancelReservation_notFound_returns404() throws Exception {
        doThrow(new ReservationNotFoundException(RESERVATION_ID))
                .when(service).cancelReservation(eq(RESERVATION_ID), any());

        mockMvc.perform(delete("/api/reservations/{id}", RESERVATION_ID)
                        .with(customerJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("RESERVATION_NOT_FOUND")));
    }

    @Test
    void cancelReservation_wrongCustomer_returns403() throws Exception {
        doThrow(new ReservationAccessDeniedException(RESERVATION_ID))
                .when(service).cancelReservation(any(), any());

        mockMvc.perform(delete("/api/reservations/{id}", RESERVATION_ID)
                        .with(customerJwt()))
                .andExpect(status().isForbidden());
    }

    // --- helpers ---

    private org.springframework.test.web.servlet.request.RequestPostProcessor customerJwt() {
        return jwt()
                .jwt(j -> j.subject(CUSTOMER_ID)
                        .claim("email", CUSTOMER_EMAIL)
                        .claim("cognito:groups", List.of("CUSTOMER")))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private ReservationResponse reservationResponse(UUID id, UUID pointId, String customerId,
                                                     ReservationStatus status) {
        Instant now = Instant.now();
        return new ReservationResponse(id, customerId, pointId, status,
                now, now.plus(15, java.time.temporal.ChronoUnit.MINUTES), now, now);
    }
}
