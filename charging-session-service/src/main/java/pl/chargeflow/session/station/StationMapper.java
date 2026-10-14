package pl.chargeflow.session.station;

import org.springframework.stereotype.Component;
import pl.chargeflow.session.station.api.ChargingPointResponse;
import pl.chargeflow.session.station.api.ChargingStationDetailsResponse;
import pl.chargeflow.session.station.api.ChargingStationSummaryResponse;
import java.util.List;


@Component
public class StationMapper {

    public ChargingStationSummaryResponse toSummary(ChargingStation station, List<ChargingPoint> activePoints) {
        int available = (int) activePoints.stream()
                .filter(ChargingPoint::isAvailableForReservation)
                .count();
        return new ChargingStationSummaryResponse(
                station.getId(),
                station.getName(),
                station.getCity(),
                station.isActive(),
                activePoints.size(),
                available
        );
    }

    public ChargingStationDetailsResponse toDetails(ChargingStation station, List<ChargingPoint> points) {
        List<ChargingPointResponse> pointResponses = points.stream()
                .map(this::toPointResponse)
                .toList();
        return new ChargingStationDetailsResponse(
                station.getId(),
                station.getName(),
                station.getAddress(),
                station.getCity(),
                station.getLatitude(),
                station.getLongitude(),
                station.isActive(),
                pointResponses
        );
    }

    public ChargingPointResponse toPointResponse(ChargingPoint cp) {
        return new ChargingPointResponse(
                cp.getId(),
                cp.getCode(),
                cp.getConnectorType(),
                cp.getMaxPowerKw(),
                cp.getPricePerKwh(),
                cp.getStatus(),
                cp.isActive()
        );
    }
}
