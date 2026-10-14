package pl.chargeflow.session.station;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.chargeflow.session.station.api.ChargingPointResponse;
import pl.chargeflow.session.station.exception.ChargingPointNotFoundException;
import pl.chargeflow.session.station.exception.InvalidChargingPointStatusTransitionException;

import java.util.UUID;

@Service
public class ChargingPointAdminService {

    private final ChargingPointRepository pointRepository;
    private final StationMapper mapper;

    public ChargingPointAdminService(ChargingPointRepository pointRepository, StationMapper mapper) {
        this.pointRepository = pointRepository;
        this.mapper = mapper;
    }

    @Transactional
    public ChargingPointResponse changeOperationalStatus(UUID chargingPointId, ChargingPointStatus requestedStatus) {
        ChargingPoint point = pointRepository.findById(chargingPointId)
                .orElseThrow(() -> new ChargingPointNotFoundException(chargingPointId));

        if (requestedStatus == ChargingPointStatus.OUT_OF_SERVICE) {
            point.markOutOfService();
        } else if (requestedStatus == ChargingPointStatus.AVAILABLE) {
            point.restoreToAvailable();
        } else {
            // RESERVED and CHARGING transitions belong to reservation/session flows (CF-010, CF-012).
            throw new InvalidChargingPointStatusTransitionException(chargingPointId, point.getStatus(), requestedStatus);
        }

        // Hibernate dirty-checking flushes the updated status at transaction commit.
        return mapper.toPointResponse(point);
    }
}
