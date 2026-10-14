package pl.chargeflow.session.station;

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
import pl.chargeflow.session.station.api.ChargingPointResponse;
import pl.chargeflow.session.station.exception.ChargingPointNotFoundException;
import pl.chargeflow.session.station.exception.InvalidChargingPointStatusTransitionException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = ChargingPointAdminController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class,
        OAuth2ResourceServerWebSecurityAutoConfiguration.class
    }
)
@Import(SecurityConfig.class)
class ChargingPointAdminControllerTest {

    @MockitoBean
    ChargingPointAdminService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mockMvc;

    private static final UUID POINT_ID = UUID.fromString("22222222-0000-0000-0000-000000000001");

    @Test
    void patchStatus_validAvailableToOutOfService_returns200WithUpdatedStatus() throws Exception {
        ChargingPointResponse response = new ChargingPointResponse(
                POINT_ID, "LUB-C01", ConnectorType.CCS,
                BigDecimal.valueOf(150), BigDecimal.valueOf(2.49),
                ChargingPointStatus.OUT_OF_SERVICE, true);

        when(service.changeOperationalStatus(eq(POINT_ID), eq(ChargingPointStatus.OUT_OF_SERVICE)))
                .thenReturn(response);

        mockMvc.perform(patch("/api/admin/charging-points/{id}/status", POINT_ID)
                        .with(operatorJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OUT_OF_SERVICE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(POINT_ID.toString())))
                .andExpect(jsonPath("$.status", is("OUT_OF_SERVICE")));
    }

    @Test
    void patchStatus_invalidBusinessTransition_returns409WithErrorCode() throws Exception {
        when(service.changeOperationalStatus(eq(POINT_ID), any()))
                .thenThrow(new InvalidChargingPointStatusTransitionException(
                        POINT_ID, ChargingPointStatus.RESERVED, ChargingPointStatus.OUT_OF_SERVICE));

        mockMvc.perform(patch("/api/admin/charging-points/{id}/status", POINT_ID)
                        .with(operatorJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OUT_OF_SERVICE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_CHARGING_POINT_STATUS_TRANSITION")));
    }

    @Test
    void patchStatus_unknownChargingPoint_returns404WithErrorCode() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(service.changeOperationalStatus(eq(unknownId), any()))
                .thenThrow(new ChargingPointNotFoundException(unknownId));

        mockMvc.perform(patch("/api/admin/charging-points/{id}/status", unknownId)
                        .with(operatorJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OUT_OF_SERVICE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("CHARGING_POINT_NOT_FOUND")));
    }

    @Test
    void patchStatus_invalidEnumValue_returns400() throws Exception {
        mockMvc.perform(patch("/api/admin/charging-points/{id}/status", POINT_ID)
                        .with(operatorJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BROKEN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void patchStatus_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(patch("/api/admin/charging-points/{id}/status", POINT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OUT_OF_SERVICE\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patchStatus_withCustomerRole_returns403() throws Exception {
        mockMvc.perform(patch("/api/admin/charging-points/{id}/status", POINT_ID)
                        .with(jwt()
                                .jwt(j -> j.subject("customer-x").claim("cognito:groups", List.of("CUSTOMER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OUT_OF_SERVICE\"}"))
                .andExpect(status().isForbidden());
    }

    // --- helpers ---

    private org.springframework.test.web.servlet.request.RequestPostProcessor operatorJwt() {
        return jwt()
                .jwt(j -> j.subject("operator-id").claim("cognito:groups", List.of("OPERATOR")))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
    }
}
