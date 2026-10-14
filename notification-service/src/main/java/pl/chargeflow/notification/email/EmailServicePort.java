package pl.chargeflow.notification.email;

import java.math.BigDecimal;
import java.time.Instant;

public interface EmailServicePort {
    void sendReservationCreated(String toEmail, String chargingPointCode, Instant validUntil);
    void sendReservationCancelled(String toEmail, String chargingPointCode);
    void sendReservationExpired(String toEmail, String chargingPointCode);
    void sendChargingStarted(String toEmail, String chargingPointCode);
    void sendChargingCompleted(String toEmail, String chargingPointCode,
                               BigDecimal energyKwh, BigDecimal totalCost);
}
