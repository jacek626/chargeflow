package pl.chargeflow.session.session;

import org.springframework.stereotype.Component;
import pl.chargeflow.session.session.api.ChargingSessionResponse;

@Component
public class ChargingSessionMapper {

    public ChargingSessionResponse toResponse(ChargingSession s) {
        return new ChargingSessionResponse(
                s.getId(),
                s.getCustomerId(),
                s.getChargingPoint().getId(),
                s.getChargingPoint().getCode(),
                s.getChargingPoint().getStation().getName(),
                s.getReservation().getId(),
                s.getStatus(),
                s.getStartedAt(),
                s.getStoppedAt(),
                s.getEnergyConsumedKwh(),
                s.getPricePerKwh(),
                s.getTotalCost(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
