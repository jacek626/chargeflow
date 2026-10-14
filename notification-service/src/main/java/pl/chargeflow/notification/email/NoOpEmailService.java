package pl.chargeflow.notification.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@ConditionalOnProperty(name = "chargeflow.ses.enabled", havingValue = "false", matchIfMissing = true)
class NoOpEmailService implements EmailServicePort {
    private static final Logger log = LoggerFactory.getLogger(NoOpEmailService.class);

    @Override public void sendReservationCreated(String e, String code, Instant v)   { log.debug("[EMAIL][NOOP] RESERVATION_CREATED → {}", e); }
    @Override public void sendReservationCancelled(String e, String code)             { log.debug("[EMAIL][NOOP] RESERVATION_CANCELLED → {}", e); }
    @Override public void sendReservationExpired(String e, String code)               { log.debug("[EMAIL][NOOP] RESERVATION_EXPIRED → {}", e); }
    @Override public void sendChargingStarted(String e, String code)                  { log.debug("[EMAIL][NOOP] CHARGING_STARTED → {}", e); }
    @Override public void sendChargingCompleted(String e, String code, BigDecimal k, BigDecimal c) { log.debug("[EMAIL][NOOP] CHARGING_COMPLETED → {}", e); }
}
