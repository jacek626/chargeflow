package pl.chargeflow.notification.consumer;

import pl.chargeflow.notification.event.DomainEventType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record IncomingEvent(
        UUID eventId,
        DomainEventType eventType,
        String eventVersion,
        Instant occurredAt,
        UUID aggregateId,
        JsonNode payload
) {}
