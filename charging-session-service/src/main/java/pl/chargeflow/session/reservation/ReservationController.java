package pl.chargeflow.session.reservation;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.chargeflow.session.reservation.api.CreateReservationRequest;
import pl.chargeflow.session.reservation.api.ReservationResponse;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse createReservation(
            @RequestBody @Valid CreateReservationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return service.createReservation(request.chargingPointId(), jwt.getSubject(), email != null ? email : "");
    }

    @GetMapping
    public List<ReservationResponse> listReservations(@AuthenticationPrincipal Jwt jwt) {
        return service.listReservations(jwt.getSubject());
    }

    @GetMapping("/{reservationId}")
    public ReservationResponse getReservation(
            @PathVariable UUID reservationId,
            @AuthenticationPrincipal Jwt jwt) {
        return service.getReservation(reservationId, jwt.getSubject());
    }

    @DeleteMapping("/{reservationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelReservation(
            @PathVariable UUID reservationId,
            @AuthenticationPrincipal Jwt jwt) {
        service.cancelReservation(reservationId, jwt.getSubject());
    }
}
