package pl.chargeflow.notification.event.payload;

public record NotificationFailedPayload(
        String customerId,
        String triggerEventType,
        String reason
) {}
