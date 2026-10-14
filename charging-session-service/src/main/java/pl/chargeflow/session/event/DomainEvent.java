package pl.chargeflow.session.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope for all domain events. Payload is typed per eventType — see payload/ subpackage.
 * eventVersion follows the contract in docs/contracts/events/README.md.
 */
public record DomainEvent(
        UUID eventId,
        DomainEventType eventType,
        String eventVersion,
        Instant occurredAt,
        UUID aggregateId,
        Object payload
) {
    public static final String CURRENT_VERSION = "1.0";
}
