package pl.chargeflow.notification.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@ConditionalOnProperty(name = "chargeflow.ses.enabled", havingValue = "true")
public class EmailService implements EmailServicePort {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final SesV2Client ses;
    private final String fromAddress;

    public EmailService(SesV2Client ses,
                        @Value("${chargeflow.ses.from-address}") String fromAddress) {
        this.ses = ses;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendReservationCreated(String toEmail, String chargingPointCode, Instant validUntil) {
        send(toEmail,
                "ChargeFlow — Reservation Confirmed",
                "Your reservation for charging point %s has been confirmed. Valid until: %s."
                        .formatted(chargingPointCode, validUntil));
    }

    @Override
    public void sendReservationCancelled(String toEmail, String chargingPointCode) {
        send(toEmail,
                "ChargeFlow — Reservation Cancelled",
                "Your reservation for charging point %s has been cancelled.".formatted(chargingPointCode));
    }

    @Override
    public void sendReservationExpired(String toEmail, String chargingPointCode) {
        send(toEmail,
                "ChargeFlow — Reservation Expired",
                "Your reservation for charging point %s has expired.".formatted(chargingPointCode));
    }

    @Override
    public void sendChargingStarted(String toEmail, String chargingPointCode) {
        send(toEmail,
                "ChargeFlow — Charging Started",
                "Charging has started on point %s. We will notify you when it is complete.".formatted(chargingPointCode));
    }

    @Override
    public void sendChargingCompleted(String toEmail, String chargingPointCode,
                                      BigDecimal energyKwh, BigDecimal totalCost) {
        send(toEmail,
                "ChargeFlow — Charging Complete",
                "Your charging session on point %s is complete. Energy: %s kWh, Cost: %s PLN."
                        .formatted(chargingPointCode, energyKwh, totalCost));
    }

    private void send(String toEmail, String subject, String body) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("[EMAIL] Cannot send '{}' — recipient email is empty", subject);
            return;
        }
        try {
            ses.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(fromAddress)
                    .destination(Destination.builder().toAddresses(toEmail).build())
                    .content(EmailContent.builder()
                            .simple(Message.builder()
                                    .subject(Content.builder().data(subject).charset("UTF-8").build())
                                    .body(Body.builder()
                                            .text(Content.builder().data(body).charset("UTF-8").build())
                                            .build())
                                    .build())
                            .build())
                    .build());
            log.info("[EMAIL] Sent '{}' to {}", subject, toEmail);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send '{}' to {}: {}", subject, toEmail, e.getMessage(), e);
            throw e;
        }
    }
}
