package pl.chargeflow.notification.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Component
@ConditionalOnProperty(name = "chargeflow.sqs.enabled", havingValue = "true")
public class SqsEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsEventConsumer.class);

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final DomainEventRouter router;
    private final String queueUrl;
    private final int maxMessages;
    private final int visibilityTimeoutSeconds;
    private final int waitTimeSeconds;

    public SqsEventConsumer(
            SqsClient sqsClient,
            ObjectMapper objectMapper,
            DomainEventRouter router,
            @Value("${chargeflow.sqs.queue-url}") String queueUrl,
            @Value("${chargeflow.sqs.max-messages:10}") int maxMessages,
            @Value("${chargeflow.sqs.visibility-timeout-seconds:30}") int visibilityTimeoutSeconds,
            @Value("${chargeflow.sqs.wait-time-seconds:20}") int waitTimeSeconds) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.router = router;
        this.queueUrl = queueUrl;
        this.maxMessages = maxMessages;
        this.visibilityTimeoutSeconds = visibilityTimeoutSeconds;
        this.waitTimeSeconds = waitTimeSeconds;
    }

    // fixedDelay=100ms so the loop is effectively continuous; long poll (waitTimeSeconds) is the real throttle
    @Scheduled(fixedDelayString = "${chargeflow.sqs.poll-interval-ms:100}")
    public void poll() {
        List<Message> messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .visibilityTimeout(visibilityTimeoutSeconds)
                .waitTimeSeconds(waitTimeSeconds)
                .build()).messages();

        for (Message message : messages) {
            processMessage(message);
        }
    }

    private void processMessage(Message message) {
        try {
            IncomingEvent event = objectMapper.readValue(message.body(), IncomingEvent.class);
            router.route(event);
            deleteMessage(message);
        } catch (Exception e) {
            log.error("[CONSUMER] Failed to process messageId={}: {}", message.messageId(), e.getMessage(), e);
            // Do not delete — SQS will redeliver after visibility timeout expires
        }
    }

    private void deleteMessage(Message message) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
    }
}
