package pl.chargeflow.notification.event.payload;

public record NotificationSentPayload(
        String customerId,
        String triggerEventType
) {}
