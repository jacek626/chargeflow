package pl.chargeflow.notification.consumer;

import pl.chargeflow.notification.event.DomainEventType;

public interface DomainEventHandler {

    DomainEventType eventType();

    void handle(IncomingEvent event);
}
