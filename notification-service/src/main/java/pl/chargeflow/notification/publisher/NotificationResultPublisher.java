package pl.chargeflow.notification.publisher;

import java.util.UUID;

public interface NotificationResultPublisher {
    void publishSent(UUID notificationId, String customerId, String triggerEventType);
    void publishFailed(UUID notificationId, String customerId, String triggerEventType, String reason);
}
