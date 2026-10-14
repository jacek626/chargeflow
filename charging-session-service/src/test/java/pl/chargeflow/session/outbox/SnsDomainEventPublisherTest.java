package pl.chargeflow.session.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.chargeflow.session.event.DomainEventType;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnsDomainEventPublisherTest {

    private SnsClient snsClient;
    private SnsDomainEventPublisher publisher;

    private static final String TOPIC_ARN = "arn:aws:sns:eu-central-1:000000000000:chargeflow-local-domain-events";

    @BeforeEach
    void setUp() {
        snsClient = mock(SnsClient.class);
        when(snsClient.publish(any(PublishRequest.class)))
                .thenReturn(PublishResponse.builder().messageId(UUID.randomUUID().toString()).build());
        publisher = new SnsDomainEventPublisher(snsClient, new ObjectMapper(), TOPIC_ARN);
    }

    @Test
    void publish_callsSnsWithCorrectTopicArn() {
        publisher.publish(outboxEvent(DomainEventType.RESERVATION_CREATED, "{\"customerId\":\"c1\"}"));

        var captor = org.mockito.ArgumentCaptor.forClass(PublishRequest.class);
        verify(snsClient).publish(captor.capture());
        assertThat(captor.getValue().topicArn()).isEqualTo(TOPIC_ARN);
    }

    @Test
    void publish_messageBodyContainsEnvelopeFields() {
        OutboxEvent event = outboxEvent(DomainEventType.CHARGING_COMPLETED,
                "{\"energyConsumedKwh\":20.0,\"totalCost\":49.80}");

        publisher.publish(event);

        var captor = org.mockito.ArgumentCaptor.forClass(PublishRequest.class);
        verify(snsClient).publish(captor.capture());
        String body = captor.getValue().message();
        assertThat(body)
                .contains("\"eventId\"")
                .contains("\"eventType\":\"CHARGING_COMPLETED\"")
                .contains("\"eventVersion\":\"1.0\"")
                .contains("\"occurredAt\"")
                .contains("\"aggregateId\"")
                .contains("\"payload\"")
                .contains("energyConsumedKwh");
    }

    @Test
    void publish_snsThrows_wrapsInRuntimeException() {
        when(snsClient.publish(any(PublishRequest.class)))
                .thenThrow(new RuntimeException("SNS unavailable"));

        assertThatThrownBy(() -> publisher.publish(
                outboxEvent(DomainEventType.RESERVATION_CREATED, "{\"customerId\":\"c1\"}")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SNS publish failed");
    }

    @Test
    void publish_payloadIsEmbeddedAsObject_notEscapedString() throws Exception {
        publisher.publish(outboxEvent(DomainEventType.RESERVATION_CREATED,
                "{\"customerId\":\"c1\",\"chargingPointId\":\"" + UUID.randomUUID() + "\"}"));

        var captor = org.mockito.ArgumentCaptor.forClass(PublishRequest.class);
        verify(snsClient).publish(captor.capture());
        String body = captor.getValue().message();
        // payload must be a JSON object, not an escaped string
        var tree = new ObjectMapper().readTree(body);
        assertThat(tree.path("payload").isObject()).isTrue();
        assertThat(tree.path("payload").path("customerId").asText()).isEqualTo("c1");
    }

    // --- helpers ---

    private OutboxEvent outboxEvent(DomainEventType type, String payload) {
        OutboxEvent e = new OutboxEvent(UUID.randomUUID(), OutboxAggregateType.RESERVATION, type, payload);
        OutboxEvent spied = org.mockito.Mockito.spy(e);
        org.mockito.Mockito.doReturn(UUID.randomUUID()).when(spied).getId();
        return spied;
    }
}
