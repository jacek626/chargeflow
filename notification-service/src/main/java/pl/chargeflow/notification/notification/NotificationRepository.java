package pl.chargeflow.notification.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.util.Map;
import java.util.UUID;

@Repository
public class NotificationRepository {

    private static final Logger log = LoggerFactory.getLogger(NotificationRepository.class);

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public NotificationRepository(
            DynamoDbClient dynamoDb,
            @Value("${chargeflow.dynamodb.table-name}") String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    /**
     * Attempts to save a new notification record.
     * Returns true if saved (new event), false if eventId already exists (duplicate).
     */
    public boolean saveIfNew(NotificationRecord record) {
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            "eventId",   str(record.eventId().toString()),
                            "customerId", str(record.customerId()),
                            "eventType",  str(record.eventType()),
                            "status",     str(record.status().name()),
                            "createdAt",  str(record.createdAt().toString())
                    ))
                    .conditionExpression("attribute_not_exists(eventId)")
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            log.debug("[NOTIFICATION] Duplicate eventId={} — skipping", record.eventId());
            return false;
        }
    }

    public void updateStatus(UUID eventId, NotificationStatus status) {
        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("eventId", str(eventId.toString())))
                .updateExpression("SET #s = :status")
                .expressionAttributeNames(Map.of("#s", "status"))
                .expressionAttributeValues(Map.of(":status", str(status.name())))
                .build());
    }

    private static AttributeValue str(String value) {
        return AttributeValue.fromS(value);
    }
}
