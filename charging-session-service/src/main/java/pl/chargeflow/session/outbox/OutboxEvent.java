package pl.chargeflow.session.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import pl.chargeflow.session.event.DomainEventType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxAggregateType aggregateType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DomainEventType eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant publishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private OutboxEventStatus status;

    protected OutboxEvent() {}

    public OutboxEvent(UUID aggregateId, OutboxAggregateType aggregateType,
                       DomainEventType eventType, String payload) {
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.status = OutboxEventStatus.NEW;
    }

    public void markPublished() {
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    public UUID getId()                     { return id; }
    public UUID getAggregateId()            { return aggregateId; }
    public OutboxAggregateType getAggregateType() { return aggregateType; }
    public DomainEventType getEventType()   { return eventType; }
    public String getPayload()              { return payload; }
    public Instant getCreatedAt()           { return createdAt; }
    public Instant getPublishedAt()         { return publishedAt; }
    public OutboxEventStatus getStatus()    { return status; }
}
