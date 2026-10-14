package pl.chargeflow.session.station;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.chargeflow.session.station.api.ChargingStationDetailsResponse;
import pl.chargeflow.session.station.api.ChargingStationSummaryResponse;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/stations")
public class StationAdminController {

    private final ChargingStationService service;

    public StationAdminController(ChargingStationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ChargingStationSummaryResponse> listAllStations() {
        return service.findAllForAdmin();
    }

    @GetMapping("/{stationId}")
    public ChargingStationDetailsResponse getStation(@PathVariable UUID stationId) {
        return service.findByIdForAdmin(stationId);
    }
}
