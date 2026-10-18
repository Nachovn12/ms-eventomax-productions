package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.event.KafkaEventEnvelope;
import cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent;
import cl.duoc.eventomax.productions.model.InventoryReservationTask;
import cl.duoc.eventomax.productions.model.OutboxEvent;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import cl.duoc.eventomax.productions.tracing.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductionStateCoordinator {

    private final ProductionRepository repository;
    private final InventoryReservationTaskService taskService;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public ProductionStateCoordinator(
            ProductionRepository repository,
            InventoryReservationTaskService taskService,
            ApplicationEventPublisher eventPublisher,
            OutboxEventRepository outboxEventRepository) {
        this(repository, taskService, eventPublisher, outboxEventRepository, new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS), null);
    }

    public ProductionStateCoordinator(
            ProductionRepository repository,
            InventoryReservationTaskService taskService,
            ApplicationEventPublisher eventPublisher,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            cl.duoc.eventomax.productions.integration.catalog.CatalogClient catalogClient) {
        this.repository = repository;
        this.taskService = taskService;
        this.eventPublisher = eventPublisher;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public record ConfirmationClaim(
            boolean alreadyConfirmed,
            Long generation,
            Production production
    ) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void validateCanConfirm(Long id) {
        Production production = repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produccion no encontrada con id: " + id));
        validateTransition(production.getStatus(), ProductionStatus.CONFIRMADO);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfirmationClaim beginConfirmation(Long id, String itemsJson) {
        Production production = repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produccion no encontrada con id: " + id));

        if (production.getStatus() == ProductionStatus.CANCELADO) {
            throw new InvalidTransitionException("No se puede confirmar una produccion en estado CANCELADO");
        }

        if (production.getStatus() == ProductionStatus.CONFIRMADO) {
            Optional<InventoryReservationTask> taskOpt = taskService.getTaskByProductionId(id);
            if (taskOpt.isPresent()) {
                InventoryReservationTask task = taskOpt.get();
                if (taskService.areItemsCompatible(task.getItemsJson(), itemsJson)
                        && !InventoryReservationTask.STATUS_COMPENSATING.equals(task.getStatus())
                        && !InventoryReservationTask.STATUS_COMPENSATED.equals(task.getStatus())) {
                    Long gen = task.getReservationGeneration() != null ? task.getReservationGeneration() : 1L;
                    return new ConfirmationClaim(true, gen, production);
                }
            }
            throw new InvalidTransitionException("Transicion invalida: CONFIRMADO -> CONFIRMADO");
        }

        validateTransition(production.getStatus(), ProductionStatus.CONFIRMADO);

        Optional<InventoryReservationTask> existingTaskOpt = taskService.getTaskByProductionId(id);
        if (existingTaskOpt.isPresent()) {
            InventoryReservationTask existing = existingTaskOpt.get();
            if (InventoryReservationTask.STATUS_PROCESSING.equals(existing.getStatus())) {
                if (taskService.areItemsCompatible(existing.getItemsJson(), itemsJson)) {
                    Long gen = (existing.getReservationGeneration() != null && existing.getReservationGeneration() > 0)
                            ? existing.getReservationGeneration() : 1L;
                    return new ConfirmationClaim(false, gen, production);
                } else {
                    throw new InvalidTransitionException(
                            "Una confirmacion con diferente equipamiento ya se encuentra en procesamiento para la produccion " + id);
                }
            }
        }

        InventoryReservationTask task = taskService.initOrUpdateIntent(id, itemsJson);
        Long generation = (task != null && task.getReservationGeneration() != null && task.getReservationGeneration() > 0)
                ? task.getReservationGeneration() : 1L;
        return new ConfirmationClaim(false, generation, production);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Production completeConfirmation(Long id, String actor, Long generation) {
        Production production = repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produccion no encontrada con id: " + id));

        if (production.getStatus() == ProductionStatus.CANCELADO) {
            throw new IllegalStateException("La produccion fue cancelada concurrentemente");
        }

        if (production.getStatus() == ProductionStatus.CONFIRMADO) {
            Optional<InventoryReservationTask> taskOpt = taskService.getTaskByProductionId(id);
            if (taskOpt.isPresent()) {
                InventoryReservationTask task = taskOpt.get();
                if (!InventoryReservationTask.STATUS_COMPENSATING.equals(task.getStatus())
                        && !InventoryReservationTask.STATUS_COMPENSATED.equals(task.getStatus())) {
                    taskService.markConfirmed(id);
                    return production;
                }
            }
            throw new InvalidTransitionException("Transicion invalida: CONFIRMADO -> CONFIRMADO");
        }

        ProductionStatus previousStatus = production.getStatus();
        validateTransition(previousStatus, ProductionStatus.CONFIRMADO);

        Optional<InventoryReservationTask> taskOpt = taskService.getTaskByProductionId(id);
        if (taskOpt.isPresent()) {
            String taskStatus = taskOpt.get().getStatus();
            if (InventoryReservationTask.STATUS_COMPENSATING.equals(taskStatus)
                    || InventoryReservationTask.STATUS_COMPENSATED.equals(taskStatus)) {
                throw new IllegalStateException("La reserva de inventario fue compensada concurrentemente");
            }
        }

        try {
            production.setStatus(ProductionStatus.CONFIRMADO);
            Production saved = repository.saveAndFlush(production);

            taskService.markConfirmed(id);
            publishStatusChangedEventAndOutbox(saved, previousStatus, actor);

            return saved;
        } catch (Exception e) {
            production.setStatus(previousStatus);
            throw e;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Production completeConfirmation(Long id, String actor) {
        return completeConfirmation(id, actor, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryBeginCompensation(Long id, Long generation, String errorMessage) {
        Production production = repository.findForUpdate(id).orElse(null);
        if (production == null) {
            return false;
        }

        if (production.getStatus() == ProductionStatus.CONFIRMADO
                || production.getStatus() == ProductionStatus.EN_MONTAJE
                || production.getStatus() == ProductionStatus.EN_EJECUCION
                || production.getStatus() == ProductionStatus.CERRADO) {
            return false;
        }

        Optional<InventoryReservationTask> taskOpt = taskService.getTaskByProductionId(id);
        if (taskOpt.isPresent()) {
            InventoryReservationTask task = taskOpt.get();
            Long currentGen = task.getReservationGeneration() != null ? task.getReservationGeneration()
                    : (task.getAttemptCount() != null ? task.getAttemptCount().longValue() : null);
            if (generation != null && currentGen != null && currentGen > generation) {
                return false;
            }
            if (InventoryReservationTask.STATUS_COMPENSATED.equals(task.getStatus())) {
                return true;
            }
        }

        taskService.markCompensating(id, errorMessage);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeCompensation(Long id, Long generation) {
        taskService.markCompensated(id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Production executeLocalCancellation(Long id, String actor) {
        Production production = repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produccion no encontrada con id: " + id));

        ProductionStatus previousStatus = production.getStatus();
        validateTransition(previousStatus, ProductionStatus.CANCELADO);

        production.setStatus(ProductionStatus.CANCELADO);
        Production saved = repository.saveAndFlush(production);

        if (previousStatus == ProductionStatus.CONFIRMADO || taskService.getTaskByProductionId(id).isPresent()) {
            taskService.markCompensating(id, "Produccion cancelada");
        }

        publishStatusChangedEventAndOutbox(saved, previousStatus, actor);
        return saved;
    }

    @Transactional
    public Production executeStandardStatusUpdate(Long id, ProductionStatus nextStatus, String actor) {
        Production production = repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produccion no encontrada con id: " + id));

        ProductionStatus previousStatus = production.getStatus();
        validateTransition(previousStatus, nextStatus);

        production.setStatus(nextStatus);
        Production saved = repository.saveAndFlush(production);

        publishStatusChangedEventAndOutbox(saved, previousStatus, actor);
        return saved;
    }

    private void publishStatusChangedEventAndOutbox(Production updated, ProductionStatus previousStatus, String actor) {
        Instant now = Instant.now();
        var trace = TraceContext.current();
        ProductionStatusChangedEvent statusEvent = new ProductionStatusChangedEvent(
                updated.getId(),
                updated.getOrganizerId(),
                updated.getName(),
                previousStatus,
                updated.getStatus(),
                updated.getScheduledAt(),
                updated.getLocation(),
                now
        );
        eventPublisher.publishEvent(statusEvent);

        try {
            KafkaEventEnvelope<ProductionStatusChangedEvent> envelope =
                    new KafkaEventEnvelope<>(
                            UUID.randomUUID(),
                            "ProductionStatusChanged",
                            now,
                            trace.traceId(),
                            trace.correlationId(),
                            actor,
                            statusEvent
                    );

            String jsonPayload = objectMapper.writeValueAsString(envelope);
            OutboxEvent outboxEvent = new OutboxEvent(
                    "Production",
                    String.valueOf(updated.getId()),
                    "ProductionStatusChanged",
                    jsonPayload
            );
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            throw new RuntimeException("Error al registrar evento de cambio de estado en Outbox", e);
        }
    }

    private static final Map<ProductionStatus, Set<ProductionStatus>> ALLOWED_TRANSITIONS =
            Map.of(
                    ProductionStatus.SOLICITADO,  EnumSet.of(ProductionStatus.CONFIRMADO,
                                                             ProductionStatus.CANCELADO),
                    ProductionStatus.CONFIRMADO,  EnumSet.of(ProductionStatus.EN_MONTAJE,
                                                             ProductionStatus.CANCELADO),
                    ProductionStatus.EN_MONTAJE,  EnumSet.of(ProductionStatus.EN_EJECUCION,
                                                             ProductionStatus.CANCELADO),
                    ProductionStatus.EN_EJECUCION, EnumSet.of(ProductionStatus.CERRADO,
                                                              ProductionStatus.CANCELADO),
                    ProductionStatus.CERRADO,     EnumSet.noneOf(ProductionStatus.class),
                    ProductionStatus.CANCELADO,   EnumSet.noneOf(ProductionStatus.class)
            );

    public void validateTransition(ProductionStatus current, ProductionStatus next) {
        Set<ProductionStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(
                current, EnumSet.noneOf(ProductionStatus.class));

        if (!allowed.contains(next)) {
            throw new InvalidTransitionException(
                    "Transicion invalida: " + current + " -> " + next
                    + ". Transiciones permitidas desde " + current + ": " + allowed);
        }
    }
}

