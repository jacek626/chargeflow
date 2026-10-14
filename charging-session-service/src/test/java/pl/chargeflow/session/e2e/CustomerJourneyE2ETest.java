package pl.chargeflow.session.e2e;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.chargeflow.session.station.ChargingPointRepository;
import pl.chargeflow.session.station.ChargingPointStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack end-to-end tests: HTTP → Controller → Service → Repository → DB (Testcontainers).
 * No mocked services — the complete application stack runs against a real PostgreSQL container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class CustomerJourneyE2ETest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ChargingPointRepository pointRepository;

    @PersistenceContext
    EntityManager entityManager;

    private static final String CUSTOMER_ID    = "cust-e2e-1";
    private static final String CUSTOMER_EMAIL = "e2e@example.com";

    // LUB-C01 — used by @Transactional tests (rolled back after each)
    private static final UUID LUB_C01 = UUID.fromString("22222222-0000-0000-0000-000000000001");

    // LUB-F01 — dedicated to the concurrent HTTP test (NOT_SUPPORTED, commits real data)
    private static final UUID LUB_F01 = UUID.fromString("22222222-0000-0000-0000-000000000006");

    // --- happy path ---

    @Test
    void fullCustomerJourney_reserve_start_stop_completesSuccessfully() throws Exception {
        // Step 1: reserve
        String reservationId = createReservation(LUB_C01, CUSTOMER_ID);
        entityManager.flush();
        entityManager.clear();

        // Step 2: start session
        String sessionId = startSession(reservationId, CUSTOMER_ID);
        entityManager.flush();
        entityManager.clear();

        // Step 3: stop session — expect COMPLETED with cost/energy calculated
        mockMvc.perform(post("/api/charging-sessions/{id}/stop", sessionId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(sessionId)))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.stoppedAt", notNullValue()))
                .andExpect(jsonPath("$.energyConsumedKwh", notNullValue()))
                .andExpect(jsonPath("$.totalCost", notNullValue()));
    }

    @Test
    void fullCustomerJourney_afterStop_chargingPointIsAvailableForNextCustomer() throws Exception {
        String reservationId = createReservation(LUB_C01, CUSTOMER_ID);
        entityManager.flush();
        entityManager.clear();

        String sessionId = startSession(reservationId, CUSTOMER_ID);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/charging-sessions/{id}/stop", sessionId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk());

        entityManager.flush();
        entityManager.clear();

        assertThat(pointRepository.findById(LUB_C01).orElseThrow().getStatus())
                .isEqualTo(ChargingPointStatus.AVAILABLE);
    }

    // --- reservation expiry ---

    @Test
    void expiredReservation_startSession_returns409WithExpiredCode() throws Exception {
        String reservationId = createReservation(LUB_C01, CUSTOMER_ID);
        entityManager.flush();
        entityManager.clear();

        // Directly mark reservation as EXPIRED and release the point (simulates expiration job)
        markReservationExpired(UUID.fromString(reservationId));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/charging-sessions")
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationId\":\"" + reservationId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RESERVATION_EXPIRED")));
    }

    @Test
    void cancelledReservation_startSession_returns409() throws Exception {
        String reservationId = createReservation(LUB_C01, CUSTOMER_ID);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(delete("/api/reservations/{id}", reservationId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isNoContent());

        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/charging-sessions")
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationId\":\"" + reservationId + "\"}"))
                .andExpect(status().isConflict());
    }

    // --- security ---

    @Test
    void createReservation_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chargingPointId\":\"" + LUB_C01 + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void stopSession_wrongCustomer_returns403() throws Exception {
        String reservationId = createReservation(LUB_C01, CUSTOMER_ID);
        entityManager.flush();
        entityManager.clear();

        String sessionId = startSession(reservationId, CUSTOMER_ID);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/charging-sessions/{id}/stop", sessionId)
                        .with(customerJwt("cust-other")))
                .andExpect(status().isForbidden());
    }

    // --- concurrent reservations (spec requirement, CF-026) ---
    // Runs outside @Transactional so each HTTP request commits in its own transaction.

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentReservations_samePoint_exactlyOneSucceeds() throws InterruptedException {
        AtomicInteger http201 = new AtomicInteger(0);
        AtomicInteger http409 = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(2);

        Runnable attempt = () -> {
            try {
                startLatch.await();
                MvcResult result = mockMvc.perform(post("/api/reservations")
                                .with(customerJwt("cust-thread-" + Thread.currentThread().getId()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"chargingPointId\":\"" + LUB_F01 + "\"}"))
                        .andReturn();
                int httpStatus = result.getResponse().getStatus();
                if (httpStatus == 201) http201.incrementAndGet();
                else if (httpStatus == 409) http409.incrementAndGet();
            } catch (Exception e) {
                // Optimistic lock or DB constraint also counts as a conflict
                http409.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(attempt);
        executor.submit(attempt);
        startLatch.countDown();

        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(http201.get()).isEqualTo(1);
        assertThat(http409.get()).isEqualTo(1);
    }

    // --- helpers ---

    private String createReservation(UUID pointId, String customerId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reservations")
                        .with(customerJwt(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chargingPointId\":\"" + pointId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return id(result);
    }

    private String startSession(String reservationId, String customerId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/charging-sessions")
                        .with(customerJwt(customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationId\":\"" + reservationId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return id(result);
    }

    private String id(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }

    private void markReservationExpired(UUID reservationId) {
        entityManager.createQuery("""
                UPDATE ChargingReservation r
                SET r.status = pl.chargeflow.session.reservation.ReservationStatus.EXPIRED,
                    r.validUntil = :past
                WHERE r.id = :id
                """)
                .setParameter("past", Instant.now().minus(1, ChronoUnit.MINUTES))
                .setParameter("id", reservationId)
                .executeUpdate();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor customerJwt(String customerId) {
        return jwt()
                .jwt(j -> j.subject(customerId)
                        .claim("email", CUSTOMER_EMAIL)
                        .claim("cognito:groups", List.of("CUSTOMER")))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }
}
