package pl.chargeflow.session.session;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.chargeflow.session.session.api.ChargingSessionResponse;
import pl.chargeflow.session.session.api.StartChargingSessionRequest;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/charging-sessions")
public class ChargingSessionController {

    private final ChargingSessionService service;

    public ChargingSessionController(ChargingSessionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChargingSessionResponse startSession(
            @RequestBody @Valid StartChargingSessionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.startSession(request.reservationId(), jwt.getSubject());
    }

    @PostMapping("/{sessionId}/stop")
    public ChargingSessionResponse stopSession(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return service.stopSession(sessionId, jwt.getSubject());
    }

    @GetMapping
    public List<ChargingSessionResponse> listSessions(@AuthenticationPrincipal Jwt jwt) {
        return service.listSessions(jwt.getSubject());
    }

    @GetMapping("/{sessionId}")
    public ChargingSessionResponse getSession(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return service.getSession(sessionId, jwt.getSubject());
    }
}
