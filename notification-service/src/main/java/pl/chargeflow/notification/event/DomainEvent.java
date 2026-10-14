package pl.chargeflow.notification.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope for all domain events received from SQS.
 * Payload is deserialized per eventType — see payload/ subpackage.
 */
public record DomainEvent(
        UUID eventId,
        DomainEventType eventType,
        String eventVersion,
        Instant occurredAt,
        UUID aggregateId,
        Object payload
) {}
