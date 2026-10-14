package pl.chargeflow.session.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Active when SNS is disabled (default for local dev and tests). SnsDomainEventPublisher takes over when chargeflow.sns.enabled=true.
@Component
@ConditionalOnProperty(name = "chargeflow.sns.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingDomainEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("[OUTBOX] event={} aggregateType={} aggregateId={} payload={}",
                event.getEventType(), event.getAggregateType(),
                event.getAggregateId(), event.getPayload());
    }
}
