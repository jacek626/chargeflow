package pl.chargeflow.session.station;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChargingPointRepository extends JpaRepository<ChargingPoint, UUID> {

    // Batch-loads all active points for a set of stations — avoids N+1 on list endpoint.
    @Query("SELECT cp FROM ChargingPoint cp WHERE cp.station.id IN :stationIds AND cp.active = true ORDER BY cp.code")
    List<ChargingPoint> findActiveByStationIds(@Param("stationIds") List<UUID> stationIds);

    @Query("SELECT cp FROM ChargingPoint cp WHERE cp.station.id = :stationId AND cp.active = true ORDER BY cp.code")
    List<ChargingPoint> findActiveByStationId(@Param("stationId") UUID stationId);

    @Query("SELECT cp FROM ChargingPoint cp WHERE cp.station.id = :stationId ORDER BY cp.code")
    List<ChargingPoint> findAllByStationId(@Param("stationId") UUID stationId);

    @Query("SELECT cp FROM ChargingPoint cp WHERE cp.station.id IN :stationIds ORDER BY cp.code")
    List<ChargingPoint> findAllByStationIds(@Param("stationIds") List<UUID> stationIds);

    Optional<ChargingPoint> findByCode(String code);
}
