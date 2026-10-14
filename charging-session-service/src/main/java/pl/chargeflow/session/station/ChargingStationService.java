package pl.chargeflow.session.station;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.chargeflow.session.station.api.ChargingStationDetailsResponse;
import pl.chargeflow.session.station.api.ChargingStationSummaryResponse;
import pl.chargeflow.session.station.exception.ChargingStationNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ChargingStationService {

    private final ChargingStationRepository stationRepository;
    private final ChargingPointRepository pointRepository;
    private final StationMapper mapper;

    public ChargingStationService(ChargingStationRepository stationRepository,
                                  ChargingPointRepository pointRepository,
                                  StationMapper mapper) {
        this.stationRepository = stationRepository;
        this.pointRepository = pointRepository;
        this.mapper = mapper;
    }

    public List<ChargingStationSummaryResponse> findAllActive() {
        List<ChargingStation> stations = stationRepository.findByActiveTrueOrderByName();
        if (stations.isEmpty()) {
            return List.of();
        }
        List<UUID> stationIds = stations.stream().map(ChargingStation::getId).toList();
        // Single batch query — avoids N+1 per station.
        List<ChargingPoint> allPoints = pointRepository.findActiveByStationIds(stationIds);

        Map<UUID, List<ChargingPoint>> pointsByStation = allPoints.stream()
                .collect(Collectors.groupingBy(cp -> cp.getStation().getId()));

        return stations.stream()
                .map(s -> mapper.toSummary(s, pointsByStation.getOrDefault(s.getId(), List.of())))
                .toList();
    }

    public ChargingStationDetailsResponse findById(UUID id) {
        ChargingStation station = stationRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ChargingStationNotFoundException(id));
        List<ChargingPoint> points = pointRepository.findActiveByStationId(id);
        return mapper.toDetails(station, points);
    }

    public List<ChargingStationSummaryResponse> findAllForAdmin() {
        List<ChargingStation> stations = stationRepository.findAllByOrderByName();
        if (stations.isEmpty()) {
            return List.of();
        }
        List<UUID> stationIds = stations.stream().map(ChargingStation::getId).toList();
        List<ChargingPoint> allPoints = pointRepository.findAllByStationIds(stationIds);
        Map<UUID, List<ChargingPoint>> pointsByStation = allPoints.stream()
                .collect(Collectors.groupingBy(cp -> cp.getStation().getId()));
        return stations.stream()
                .map(s -> mapper.toSummary(s, pointsByStation.getOrDefault(s.getId(), List.of())))
                .toList();
    }

    public ChargingStationDetailsResponse findByIdForAdmin(UUID id) {
        ChargingStation station = stationRepository.findById(id)
                .orElseThrow(() -> new ChargingStationNotFoundException(id));
        List<ChargingPoint> points = pointRepository.findAllByStationId(id);
        return mapper.toDetails(station, points);
    }
}
