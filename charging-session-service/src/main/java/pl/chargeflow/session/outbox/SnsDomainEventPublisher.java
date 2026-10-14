package pl.chargeflow.session.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnProperty(name = "chargeflow.sns.enabled", havingValue = "true")
public class SnsDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SnsDomainEventPublisher.class);
    private static final String VERSION = "1.0";

    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final String topicArn;

    public SnsDomainEventPublisher(SnsClient snsClient, ObjectMapper objectMapper,
                                   @Value("${chargeflow.sns.topic-arn}") String topicArn) {
        this.snsClient = snsClient;
        this.objectMapper = objectMapper;
        this.topicArn = topicArn;
    }

    @Override
    public void publish(OutboxEvent event) {
        try {
            String body = buildEnvelope(event);
            snsClient.publish(PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(body)
                    .build());
            log.info("[SNS] Published event={} aggregateId={}", event.getEventType(), event.getAggregateId());
        } catch (Exception e) {
            log.error("[SNS] Failed to publish event={} aggregateId={}: {}",
                    event.getEventType(), event.getAggregateId(), e.getMessage(), e);
            throw new RuntimeException("SNS publish failed for eventType=" + event.getEventType(), e);
        }
    }

    private String buildEnvelope(OutboxEvent event) throws Exception {
        JsonNode payloadNode = objectMapper.readTree(event.getPayload());

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", event.getId().toString());
        envelope.put("eventType", event.getEventType().name());
        envelope.put("eventVersion", VERSION);
        envelope.put("occurredAt", event.getCreatedAt().toString());
        envelope.put("aggregateId", event.getAggregateId().toString());
        envelope.set("payload", payloadNode);

        return objectMapper.writeValueAsString(envelope);
    }
}
