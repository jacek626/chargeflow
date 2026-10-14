package pl.chargeflow.session.station;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChargingStationRepository extends JpaRepository<ChargingStation, UUID> {

    List<ChargingStation> findByActiveTrueOrderByName();

    List<ChargingStation> findAllByOrderByName();

    Optional<ChargingStation> findByIdAndActiveTrue(UUID id);
}
