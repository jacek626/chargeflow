package pl.chargeflow.session.reservation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.chargeflow.session.reservation.api.ReservationResponse;

import java.util.List;

@RestController
@RequestMapping("/api/admin/reservations")
public class ReservationAdminController {

    private final ReservationService service;

    public ReservationAdminController(ReservationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ReservationResponse> listAllReservations() {
        return service.listAllReservations();
    }
}
