package pl.chargeflow.session.session;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.chargeflow.session.session.api.ChargingSessionResponse;

import java.util.List;

@RestController
@RequestMapping("/api/admin/charging-sessions")
public class ChargingSessionAdminController {

    private final ChargingSessionService service;

    public ChargingSessionAdminController(ChargingSessionService service) {
        this.service = service;
    }

    @GetMapping
    public List<ChargingSessionResponse> listAllSessions() {
        return service.listAllSessions();
    }
}
