package pl.chargeflow.session.reservation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ChargingReservationRepository extends JpaRepository<ChargingReservation, UUID> {

    List<ChargingReservation> findByCustomerIdOrderByReservedAtDesc(String customerId);

    List<ChargingReservation> findAllByOrderByReservedAtDesc();

    // JOIN FETCH avoids N+1 when releasing each reservation's charging point.
    @Query("""
            SELECT r FROM ChargingReservation r
            JOIN FETCH r.chargingPoint
            WHERE r.validUntil < :now
              AND r.status IN :activeStatuses
            """)
    List<ChargingReservation> findActiveExpiredBefore(
            @Param("now") Instant now,
            @Param("activeStatuses") List<ReservationStatus> activeStatuses);
}
