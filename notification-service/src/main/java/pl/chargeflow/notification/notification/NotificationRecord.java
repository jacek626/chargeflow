package pl.chargeflow.notification.notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationRecord(
        UUID eventId,
        String customerId,
        String eventType,
        NotificationStatus status,
        Instant createdAt
) {
    public static NotificationRecord pending(UUID eventId, String customerId, String eventType) {
        return new NotificationRecord(eventId, customerId, eventType, NotificationStatus.PENDING, Instant.now());
    }
}
