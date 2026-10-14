package pl.chargeflow.session.station;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.chargeflow.session.config.SecurityConfig;
import pl.chargeflow.session.station.api.ChargingPointResponse;
import pl.chargeflow.session.station.api.ChargingStationDetailsResponse;
import pl.chargeflow.session.station.api.ChargingStationSummaryResponse;
import pl.chargeflow.session.station.exception.ChargingStationNotFoundException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// /api/stations/** is public (permitAll) — no JWT needed in requests.
// JwtDecoder mock satisfies SecurityConfig wiring without hitting Cognito JWKS endpoint.
@WebMvcTest(
    controllers = ChargingStationController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class,
        OAuth2ResourceServerWebSecurityAutoConfiguration.class
    }
)
@Import(SecurityConfig.class)
class ChargingStationControllerTest {

    @MockitoBean
    ChargingStationService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mockMvc;

    private static final UUID STATION_ID = UUID.fromString("11111111-0000-0000-0000-000000000001");

    @Test
    void listActiveStationsReturnsHttp200WithThreeStations() throws Exception {
        when(service.findAllActive()).thenReturn(List.of(
                summary(STATION_ID, "Lublin Centrum", "Lublin", 3, 3),
                summary(UUID.randomUUID(), "Lublin Czechów", "Lublin", 2, 2),
                summary(UUID.randomUUID(), "Lublin Felin", "Lublin", 2, 2)
        ));

        mockMvc.perform(get("/api/stations").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].name", is("Lublin Centrum")))
                .andExpect(jsonPath("$[0].city", is("Lublin")))
                .andExpect(jsonPath("$[0].chargingPointCount", is(3)))
                .andExpect(jsonPath("$[0].availableChargingPointCount", is(3)));
    }

    @Test
    void getStationDetailsReturnsHttp200WithChargingPoints() throws Exception {
        ChargingPointResponse point = new ChargingPointResponse(
                UUID.randomUUID(), "LUB-C01", ConnectorType.CCS,
                BigDecimal.valueOf(150), BigDecimal.valueOf(2.49),
                ChargingPointStatus.AVAILABLE, true);

        ChargingStationDetailsResponse details = new ChargingStationDetailsResponse(
                STATION_ID, "Lublin Centrum", "ul. Krakowskie Przedmieście 1", "Lublin",
                BigDecimal.valueOf(51.246454), BigDecimal.valueOf(22.561474), true, List.of(point));

        when(service.findById(STATION_ID)).thenReturn(details);

        mockMvc.perform(get("/api/stations/{id}", STATION_ID).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(STATION_ID.toString())))
                .andExpect(jsonPath("$.name", is("Lublin Centrum")))
                .andExpect(jsonPath("$.chargingPoints", hasSize(1)))
                .andExpect(jsonPath("$.chargingPoints[0].code", is("LUB-C01")))
                .andExpect(jsonPath("$.chargingPoints[0].status", is("AVAILABLE")));
    }

    @Test
    void getStationDetailsReturns404WithErrorCodeForUnknownId() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(service.findById(unknownId)).thenThrow(new ChargingStationNotFoundException(unknownId));

        mockMvc.perform(get("/api/stations/{id}", unknownId).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("CHARGING_STATION_NOT_FOUND")))
                .andExpect(jsonPath("$.message", is("Charging station was not found.")));
    }

    private ChargingStationSummaryResponse summary(UUID id, String name, String city,
                                                    int total, int available) {
        return new ChargingStationSummaryResponse(id, name, city, true, total, available);
    }
}
