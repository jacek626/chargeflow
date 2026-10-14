package pl.chargeflow.session.station;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.chargeflow.session.station.api.ChargingStationDetailsResponse;
import pl.chargeflow.session.station.api.ChargingStationSummaryResponse;
import java.util.List;
import java.util.UUID;

// Authentication/authorization will be enforced in CF-022 when Cognito is configured.
@RestController
@RequestMapping("/api/stations")
public class ChargingStationController {

    private final ChargingStationService service;

    public ChargingStationController(ChargingStationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ChargingStationSummaryResponse> listActiveStations() {
        return service.findAllActive();
    }

    @GetMapping("/{stationId}")
    public ChargingStationDetailsResponse getStationDetails(@PathVariable UUID stationId) {
        return service.findById(stationId);
    }
}
