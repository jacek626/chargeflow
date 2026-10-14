package pl.chargeflow.notification.publisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "chargeflow.sns.enabled", havingValue = "true")
public class SnsNotificationResultPublisher implements NotificationResultPublisher {

    private static final Logger log = LoggerFactory.getLogger(SnsNotificationResultPublisher.class);
    private static final String VERSION = "1.0";

    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final String topicArn;

    public SnsNotificationResultPublisher(SnsClient snsClient, ObjectMapper objectMapper,
                                          @Value("${chargeflow.sns.notification-topic-arn}") String topicArn) {
        this.snsClient = snsClient;
        this.objectMapper = objectMapper;
        this.topicArn = topicArn;
    }

    @Override
    public void publishSent(UUID notificationId, String customerId, String triggerEventType) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("customerId", customerId);
        payload.put("triggerEventType", triggerEventType);
        publish(notificationId, "NOTIFICATION_SENT", payload);
    }

    @Override
    public void publishFailed(UUID notificationId, String customerId, String triggerEventType, String reason) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("customerId", customerId);
        payload.put("triggerEventType", triggerEventType);
        payload.put("reason", reason);
        publish(notificationId, "NOTIFICATION_FAILED", payload);
    }

    private void publish(UUID notificationId, String eventType, ObjectNode payload) {
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("eventId", UUID.randomUUID().toString());
            envelope.put("eventType", eventType);
            envelope.put("eventVersion", VERSION);
            envelope.put("occurredAt", Instant.now().toString());
            envelope.put("aggregateId", notificationId.toString());
            envelope.set("payload", payload);

            String body = objectMapper.writeValueAsString(envelope);
            snsClient.publish(PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(body)
                    .build());
            log.info("[SNS] Published {} notificationId={}", eventType, notificationId);
        } catch (Exception e) {
            log.error("[SNS] Failed to publish {} notificationId={}: {}", eventType, notificationId, e.getMessage(), e);
        }
    }
}
