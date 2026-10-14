package pl.chargeflow.notification.consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.chargeflow.notification.event.DomainEventType;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqsEventConsumerTest {

    private SqsClient sqsClient;
    private DomainEventRouter router;
    private SqsEventConsumer consumer;

    private static final String QUEUE_URL = "http://localhost:4566/000000000000/chargeflow-local-notifications";

    @BeforeEach
    void setUp() {
        sqsClient = mock(SqsClient.class);
        router = mock(DomainEventRouter.class);
        consumer = new SqsEventConsumer(sqsClient, new ObjectMapper(), router,
                QUEUE_URL, 10, 30, 20);
    }

    @Test
    void poll_validMessage_routesAndDeletes() {
        String body = validEnvelope(DomainEventType.RESERVATION_CREATED);
        Message message = message("receipt-1", body);
        stubReceive(message);

        consumer.poll();

        verify(router).route(any(IncomingEvent.class));
        verify(sqsClient).deleteMessage((DeleteMessageRequest) DeleteMessageRequest.builder()
                .queueUrl(QUEUE_URL).receiptHandle("receipt-1").build());
    }

    @Test
    void poll_routerThrows_messageNotDeleted() {
        String body = validEnvelope(DomainEventType.CHARGING_STARTED);
        Message message = message("receipt-2", body);
        stubReceive(message);
        org.mockito.Mockito.doThrow(new RuntimeException("handler error"))
                .when(router).route(any());

        consumer.poll(); // must not propagate the exception

        verify(sqsClient, never()).deleteMessage(any(software.amazon.awssdk.services.sqs.model.DeleteMessageRequest.class));
    }

    @Test
    void poll_invalidJson_messageNotDeleted() {
        Message message = message("receipt-3", "not-valid-json");
        stubReceive(message);

        consumer.poll();

        verify(router, never()).route(any());
        verify(sqsClient, never()).deleteMessage(any(software.amazon.awssdk.services.sqs.model.DeleteMessageRequest.class));
    }

    @Test
    void poll_emptyQueue_doesNotCallRouter() {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

        consumer.poll();

        verify(router, never()).route(any());
    }

    // --- helpers ---

    private void stubReceive(Message message) {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(message).build());
    }

    private Message message(String receiptHandle, String body) {
        return Message.builder()
                .messageId(UUID.randomUUID().toString())
                .receiptHandle(receiptHandle)
                .body(body)
                .build();
    }

    private String validEnvelope(DomainEventType eventType) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "eventVersion": "1.0",
                  "occurredAt": "2026-10-07T10:00:00Z",
                  "aggregateId": "%s",
                  "payload": { "customerId": "cust-1", "chargingPointId": "%s" }
                }
                """.formatted(UUID.randomUUID(), eventType.name(), UUID.randomUUID(), UUID.randomUUID());
    }
}
