package pl.chargeflow.notification.consumer;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.chargeflow.notification.event.DomainEventType;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DomainEventRouter {

    private static final Logger log = LoggerFactory.getLogger(DomainEventRouter.class);

    private final Map<DomainEventType, DomainEventHandler> handlers;
    private final MeterRegistry meterRegistry;

    public DomainEventRouter(List<DomainEventHandler> handlerList, MeterRegistry meterRegistry) {
        handlers = handlerList.stream()
                .collect(Collectors.toUnmodifiableMap(DomainEventHandler::eventType, h -> h));
        this.meterRegistry = meterRegistry;
    }

    public void route(IncomingEvent event) {
        DomainEventHandler handler = handlers.get(event.eventType());
        if (handler == null) {
            log.debug("[CONSUMER] No handler registered for eventType={} — skipping", event.eventType());
            return;
        }
        String eventType = event.eventType().name();
        try {
            handler.handle(event);
            meterRegistry.counter("chargeflow.notifications.sent", "eventType", eventType).increment();
        } catch (RuntimeException e) {
            meterRegistry.counter("chargeflow.notifications.failed", "eventType", eventType).increment();
            throw e;
        }
    }
}
