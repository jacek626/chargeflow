package pl.chargeflow.notification.consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.chargeflow.notification.event.DomainEventType;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomainEventRouterTest {

    private DomainEventHandler handler;
    private DomainEventRouter router;

    @BeforeEach
    void setUp() {
        handler = mock(DomainEventHandler.class);
        when(handler.eventType()).thenReturn(DomainEventType.RESERVATION_CREATED);
        router = new DomainEventRouter(List.of(handler), new SimpleMeterRegistry());
    }

    @Test
    void route_matchingEventType_callsHandler() {
        IncomingEvent event = incomingEvent(DomainEventType.RESERVATION_CREATED);

        router.route(event);

        verify(handler).handle(event);
    }

    @Test
    void route_noMatchingHandler_doesNotThrow() {
        IncomingEvent event = incomingEvent(DomainEventType.CHARGING_COMPLETED);

        router.route(event); // no handler for CHARGING_COMPLETED registered

        verify(handler, never()).handle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void route_multipleHandlers_callsOnlyMatching() {
        DomainEventHandler cancelledHandler = mock(DomainEventHandler.class);
        when(cancelledHandler.eventType()).thenReturn(DomainEventType.RESERVATION_CANCELLED);
        router = new DomainEventRouter(List.of(handler, cancelledHandler), new SimpleMeterRegistry());

        IncomingEvent event = incomingEvent(DomainEventType.RESERVATION_CANCELLED);
        router.route(event);

        verify(cancelledHandler).handle(event);
        verify(handler, never()).handle(org.mockito.ArgumentMatchers.any());
    }

    private IncomingEvent incomingEvent(DomainEventType type) {
        return new IncomingEvent(UUID.randomUUID(), type, "1.0", Instant.now(),
                UUID.randomUUID(), JsonNodeFactory.instance.objectNode());
    }
}
