package pl.chargeflow.session.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository repository;
    private final DomainEventPublisher publisher;

    public OutboxPoller(OutboxEventRepository repository, DomainEventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${chargeflow.outbox.poll-interval-ms:5000}")
    @Transactional
    public void pollAndPublish() {
        List<OutboxEvent> events = repository.findUnpublishedBatch(BATCH_SIZE);
        if (events.isEmpty()) return;

        for (OutboxEvent event : events) {
            publisher.publish(event);
            event.markPublished();
        }
        log.info("[OUTBOX] Published {} event(s)", events.size());
    }
}
