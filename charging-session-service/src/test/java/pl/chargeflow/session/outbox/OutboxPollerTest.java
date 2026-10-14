package pl.chargeflow.session.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.chargeflow.session.event.DomainEventType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPollerTest {

    private OutboxEventRepository repository;
    private DomainEventPublisher publisher;
    private OutboxPoller poller;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        publisher = mock(DomainEventPublisher.class);
        poller = new OutboxPoller(repository, publisher);
    }

    @Test
    void pollAndPublish_newEvents_callsPublisherForEach() {
        OutboxEvent event = newEvent();
        when(repository.findUnpublishedBatch(100)).thenReturn(List.of(event));

        poller.pollAndPublish();

        verify(publisher).publish(event);
    }

    @Test
    void pollAndPublish_newEvents_marksEachAsPublished() {
        OutboxEvent event = newEvent();
        when(repository.findUnpublishedBatch(100)).thenReturn(List.of(event));

        poller.pollAndPublish();

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    void pollAndPublish_noNewEvents_doesNotCallPublisher() {
        when(repository.findUnpublishedBatch(100)).thenReturn(List.of());

        poller.pollAndPublish();

        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pollAndPublish_multipleEvents_publishesAll() {
        OutboxEvent e1 = newEvent();
        OutboxEvent e2 = newEvent();
        when(repository.findUnpublishedBatch(100)).thenReturn(List.of(e1, e2));

        poller.pollAndPublish();

        verify(publisher).publish(e1);
        verify(publisher).publish(e2);
        assertThat(e1.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(e2.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    // --- helpers ---

    private OutboxEvent newEvent() {
        return new OutboxEvent(UUID.randomUUID(), OutboxAggregateType.RESERVATION,
                DomainEventType.RESERVATION_CREATED,
                "{\"customerId\":\"cust-1\",\"chargingPointId\":\"" + UUID.randomUUID() + "\"}");
    }
}
