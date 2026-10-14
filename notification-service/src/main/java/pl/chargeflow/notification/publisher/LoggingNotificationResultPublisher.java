package pl.chargeflow.notification.publisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "chargeflow.sns.enabled", havingValue = "false", matchIfMissing = true)
class LoggingNotificationResultPublisher implements NotificationResultPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationResultPublisher.class);

    @Override
    public void publishSent(UUID notificationId, String customerId, String triggerEventType) {
        log.debug("[SNS][NOOP] NOTIFICATION_SENT notificationId={} customerId={} trigger={}",
                notificationId, customerId, triggerEventType);
    }

    @Override
    public void publishFailed(UUID notificationId, String customerId, String triggerEventType, String reason) {
        log.debug("[SNS][NOOP] NOTIFICATION_FAILED notificationId={} customerId={} trigger={} reason={}",
                notificationId, customerId, triggerEventType, reason);
    }
}
