package pl.chargeflow.notification.consumer.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.chargeflow.notification.consumer.DomainEventHandler;
import pl.chargeflow.notification.consumer.IncomingEvent;
import pl.chargeflow.notification.email.EmailServicePort;
import pl.chargeflow.notification.event.DomainEventType;
import pl.chargeflow.notification.event.payload.ChargingStartedPayload;
import pl.chargeflow.notification.notification.NotificationRecord;
import pl.chargeflow.notification.notification.NotificationRepository;
import pl.chargeflow.notification.notification.NotificationStatus;
import pl.chargeflow.notification.publisher.NotificationResultPublisher;
import tools.jackson.databind.ObjectMapper;

@Component
public class ChargingStartedHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ChargingStartedHandler.class);

    private final NotificationRepository repository;
    private final EmailServicePort email;
    private final ObjectMapper objectMapper;
    private final NotificationResultPublisher publisher;

    public ChargingStartedHandler(NotificationRepository repository,
                                  EmailServicePort email,
                                  ObjectMapper objectMapper,
                                  NotificationResultPublisher publisher) {
        this.repository = repository;
        this.email = email;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
    }

    @Override
    public DomainEventType eventType() {
        return DomainEventType.CHARGING_STARTED;
    }

    @Override
    public void handle(IncomingEvent event) {
        String customerId = event.payload().path("customerId").asText("?");
        NotificationRecord record = NotificationRecord.pending(
                event.eventId(), customerId, event.eventType().name());

        if (!repository.saveIfNew(record)) {
            log.debug("[NOTIFICATION] Duplicate CHARGING_STARTED eventId={} — skipped", event.eventId());
            return;
        }

        try {
            ChargingStartedPayload payload = objectMapper.treeToValue(
                    event.payload(), ChargingStartedPayload.class);
            email.sendChargingStarted(payload.customerEmail(), payload.chargingPointId().toString());
            repository.updateStatus(event.eventId(), NotificationStatus.SENT);
            publisher.publishSent(event.eventId(), customerId, event.eventType().name());
            log.info("[NOTIFICATION] CHARGING_STARTED sent eventId={}", event.eventId());
        } catch (Exception e) {
            repository.updateStatus(event.eventId(), NotificationStatus.FAILED);
            publisher.publishFailed(event.eventId(), customerId, event.eventType().name(), "EMAIL_SEND_FAILED");
            log.error("[NOTIFICATION] CHARGING_STARTED failed eventId={}: {}", event.eventId(), e.getMessage(), e);
            throw new RuntimeException("Email delivery failed for CHARGING_STARTED eventId=" + event.eventId(), e);
        }
    }
}
