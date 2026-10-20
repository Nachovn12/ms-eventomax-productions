package cl.duoc.eventomax.productions.messaging.kafka;

import cl.duoc.eventomax.productions.model.OutboxEvent;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    @org.springframework.beans.factory.annotation.Value("${eventomax.kafka.topic.productions}")
    private String topic;
    private static final int MAX_RETRIES = 3;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private OutboxRelay self;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${eventomax.outbox.poll-delay-ms:2000}", initialDelayString = "${eventomax.outbox.initial-delay-ms:0}")
    public void processOutbox() {
        self.reclaimStuckEvents();

        // Claim only when ready to send: no batch waits behind slow publishes while its lease expires.
        for (int i = 0; i < 50; i++) {
            List<OutboxEvent> claimedEvents = self.claimEventsBatch();
            if (claimedEvents.isEmpty()) return;
            OutboxEvent event = claimedEvents.getFirst();
            try {
                // Key = aggregateId (Production ID) to preserve ordering per production
                kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload())
                             .get(35, TimeUnit.SECONDS); 
                
                self.markAsSent(event);
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                log.warn("Worker obsolete: event {} was already modified by another worker", event.getId());
            } catch (Exception e) {
                log.error("Failed to publish event id {}", event.getId(), e);
                try {
                    self.handlePublishError(event, e.getMessage());
                } catch (org.springframework.orm.ObjectOptimisticLockingFailureException oe) {
                    log.warn("Worker obsolete on error handling: event {} already modified", event.getId());
                }
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return; // Retry on the next scheduled tick, not three immediate attempts.
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reclaimStuckEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(2);
        List<OutboxEvent> stuck = outboxEventRepository.findStuckEvents(cutoff);
        for (OutboxEvent event : stuck) {
            log.warn("Reclaiming stuck IN_PROGRESS event id {}", event.getId());
            event.setStatus("PENDING");
            event.setUpdatedAt(LocalDateTime.now());
            outboxEventRepository.save(event);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimEventsBatch() {
        List<OutboxEvent> events = outboxEventRepository.claimNextBatch(1);
        LocalDateTime now = LocalDateTime.now();
        for (OutboxEvent event : events) {
            event.setStatus("IN_PROGRESS");
            event.setUpdatedAt(now);
        }
        return outboxEventRepository.saveAll(events);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsSent(OutboxEvent event) {
        event.setStatus("SENT");
        event.setErrorMessage(null);
        event.setUpdatedAt(LocalDateTime.now());
        outboxEventRepository.save(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePublishError(OutboxEvent event, String errorMsg) {
        event.setRetries(event.getRetries() + 1);
        if (event.getRetries() >= MAX_RETRIES) {
            event.setStatus("ERROR");
            log.error("Event {} moved to ERROR state after {} retries", event.getId(), event.getRetries());
        } else {
            event.setStatus("PENDING"); // Will be picked up again in the next batch
        }
        
        // Sanitize error message length to avoid DB errors
        String safeError = errorMsg != null && errorMsg.length() > 500 
            ? errorMsg.substring(0, 500) : errorMsg;
            
        event.setErrorMessage(safeError);
        event.setUpdatedAt(LocalDateTime.now());
        outboxEventRepository.save(event);
    }
}
