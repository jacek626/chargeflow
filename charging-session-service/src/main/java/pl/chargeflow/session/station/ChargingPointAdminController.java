package pl.chargeflow.session.station;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.chargeflow.session.station.api.ChangeChargingPointStatusRequest;
import pl.chargeflow.session.station.api.ChargingPointResponse;

import java.util.UUID;

// Authentication/authorization (OPERATOR role) will be enforced in CF-022 when Cognito is configured.
@RestController
@RequestMapping("/api/admin/charging-points")
public class ChargingPointAdminController {

    private final ChargingPointAdminService service;

    public ChargingPointAdminController(ChargingPointAdminService service) {
        this.service = service;
    }

    @PatchMapping("/{chargingPointId}/status")
    public ChargingPointResponse changeStatus(@PathVariable UUID chargingPointId,
                                               @RequestBody @Valid ChangeChargingPointStatusRequest request) {
        return service.changeOperationalStatus(chargingPointId, request.status());
    }
}
