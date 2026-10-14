package pl.chargeflow.session.reservation;

import org.springframework.stereotype.Component;
import pl.chargeflow.session.reservation.api.ReservationResponse;

@Component
public class ReservationMapper {

    public ReservationResponse toResponse(ChargingReservation r) {
        return new ReservationResponse(
                r.getId(),
                r.getCustomerId(),
                r.getChargingPoint().getId(),
                r.getChargingPoint().getCode(),
                r.getChargingPoint().getStation().getName(),
                r.getStatus(),
                r.getReservedAt(),
                r.getValidUntil(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
