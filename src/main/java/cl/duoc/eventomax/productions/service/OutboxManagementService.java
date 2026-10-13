package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.OutboxRecoveryResult;
import cl.duoc.eventomax.productions.dto.OutboxRecoveryStatus;
import cl.duoc.eventomax.productions.model.OutboxEvent;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class OutboxManagementService {

    private static final Logger log = LoggerFactory.getLogger(OutboxManagementService.class);

    private final OutboxEventRepository outboxEventRepository;

    public OutboxManagementService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OutboxRecoveryResult retryFailedEvent(UUID outboxId, String actor) {
        log.info("Administrative outbox recovery requested: outboxId={}, actor={}", outboxId, actor);

        Optional<OutboxEvent> optionalEvent = outboxEventRepository.findByIdForUpdate(outboxId);
        if (optionalEvent.isEmpty()) {
            log.warn("Recovery failed - record not found: outboxId={}, actor={}", outboxId, actor);
            return new OutboxRecoveryResult(
                    outboxId,
                    OutboxRecoveryStatus.NOT_FOUND,
                    "Registro de outbox no encontrado",
                    null,
                    null
            );
        }

        OutboxEvent event = optionalEvent.get();
        String currentStatus = event.getStatus();

        if ("SENT".equalsIgnoreCase(currentStatus)) {
            log.info("Recovery rejected - already sent: outboxId={}, actor={}", outboxId, actor);
            return new OutboxRecoveryResult(
                    outboxId,
                    OutboxRecoveryStatus.ALREADY_SENT,
                    "El evento ya fue emitido exitosamente",
                    event.getRetries(),
                    event.getUpdatedAt()
            );
        }

        if ("PENDING".equalsIgnoreCase(currentStatus)) {
            log.info("Recovery rejected - already pending: outboxId={}, actor={}", outboxId, actor);
            return new OutboxRecoveryResult(
                    outboxId,
                    OutboxRecoveryStatus.ALREADY_PENDING,
                    "El evento ya se encuentra en estado pendiente de publicacion",
                    event.getRetries(),
                    event.getUpdatedAt()
            );
        }

        if ("IN_PROGRESS".equalsIgnoreCase(currentStatus)) {
            log.info("Recovery rejected - currently in progress: outboxId={}, actor={}", outboxId, actor);
            return new OutboxRecoveryResult(
                    outboxId,
                    OutboxRecoveryStatus.IN_PROGRESS,
                    "El evento esta siendo procesado actualmente por un worker",
                    event.getRetries(),
                    event.getUpdatedAt()
            );
        }

        if (!"ERROR".equalsIgnoreCase(currentStatus)) {
            log.warn("Recovery rejected - unrecoverable status: outboxId={}, status={}, actor={}",
                    outboxId, currentStatus, actor);
            return new OutboxRecoveryResult(
                    outboxId,
                    OutboxRecoveryStatus.BLOCKED_BY_PREDECESSOR,
                    "Estado de evento no recuperable: " + currentStatus,
                    event.getRetries(),
                    event.getUpdatedAt()
            );
        }

        // Only ERROR reaches here. Check predecessors for the same aggregate:
        long predecessors = outboxEventRepository.countUnresolvedPredecessors(
                outboxId, event.getAggregateType(), event.getAggregateId()
        );

        if (predecessors > 0) {
            log.warn("Recovery blocked by active predecessor(s): outboxId={}, aggregateId={}, predecessors={}, actor={}",
                    outboxId, event.getAggregateId(), predecessors, actor);
            return new OutboxRecoveryResult(
                    outboxId,
                    OutboxRecoveryStatus.BLOCKED_BY_PREDECESSOR,
                    "Recuperacion bloqueada: existen " + predecessors + " evento(s) previo(s) sin resolver para el agregado",
                    event.getRetries(),
                    event.getUpdatedAt()
            );
        }

        // Successful recovery: transition ERROR -> PENDING
        int oldRetries = event.getRetries();
        event.setStatus("PENDING");
        event.setRetries(0);
        event.setErrorMessage(null);
        LocalDateTime now = LocalDateTime.now();
        event.setUpdatedAt(now);
        outboxEventRepository.save(event);

        log.info("Administrative outbox recovery succeeded: outboxId={}, aggregateId={}, previousRetries={}, actor={}",
                outboxId, event.getAggregateId(), oldRetries, actor);

        return new OutboxRecoveryResult(
                outboxId,
                OutboxRecoveryStatus.RECOVERED,
                "Evento recuperado exitosamente y reprogramado a PENDING",
                oldRetries,
                now
        );
    }
}

