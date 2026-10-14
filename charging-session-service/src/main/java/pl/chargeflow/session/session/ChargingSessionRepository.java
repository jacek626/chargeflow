package pl.chargeflow.session.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChargingSessionRepository extends JpaRepository<ChargingSession, UUID> {

    List<ChargingSession> findByCustomerIdOrderByStartedAtDesc(String customerId);

    List<ChargingSession> findAllByOrderByStartedAtDesc();
}
