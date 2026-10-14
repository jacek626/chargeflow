package pl.chargeflow.notification.publisher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SnsNotificationResultPublisherTest {

    private static final String TOPIC_ARN = "arn:aws:sns:eu-central-1:000000000000:chargeflow-local-notifications";

    private SnsClient snsClient;
    private SnsNotificationResultPublisher publisher;

    @BeforeEach
    void setUp() {
        snsClient = mock(SnsClient.class);
        publisher = new SnsNotificationResultPublisher(snsClient, new ObjectMapper(), TOPIC_ARN);
    }

    @Test
    void publishSent_callsSnsPublish() {
        publisher.publishSent(UUID.randomUUID(), "customer-123", "RESERVATION_CREATED");

        verify(snsClient).publish(any(PublishRequest.class));
    }

    @Test
    void publishFailed_callsSnsPublish() {
        publisher.publishFailed(UUID.randomUUID(), "customer-123", "RESERVATION_CREATED", "EMAIL_SEND_FAILED");

        verify(snsClient).publish(any(PublishRequest.class));
    }

    @Test
    void publishSent_snsThrows_doesNotPropagate() {
        org.mockito.Mockito.doThrow(new RuntimeException("SNS down"))
                .when(snsClient).publish(any(PublishRequest.class));

        publisher.publishSent(UUID.randomUUID(), "customer-123", "CHARGING_COMPLETED");
        // no exception expected
    }

    @Test
    void publishFailed_snsThrows_doesNotPropagate() {
        org.mockito.Mockito.doThrow(new RuntimeException("SNS down"))
                .when(snsClient).publish(any(PublishRequest.class));

        publisher.publishFailed(UUID.randomUUID(), "customer-123", "CHARGING_COMPLETED", "EMAIL_SEND_FAILED");
        // no exception expected
    }
}
