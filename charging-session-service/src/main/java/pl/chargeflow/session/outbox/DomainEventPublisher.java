package pl.chargeflow.session.outbox;

/**
 * Publishes a domain event to the message broker (SNS in production).
 * Default implementation logs the event; real SNS wiring happens in CF-017.
 */
public interface DomainEventPublisher {
    void publish(OutboxEvent event);
}
