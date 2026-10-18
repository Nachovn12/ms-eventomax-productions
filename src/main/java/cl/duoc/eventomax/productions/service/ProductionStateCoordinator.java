package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.event.KafkaEventEnvelope;
import cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void validateCanConfirm(Long id) {
        Production production = repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produccion no encontrada con id: " + id));
        validateTransition(production.getStatus(), ProductionStatus.CONFIRMADO);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Production completeConfirmation(Long id, String actor) {
        Production production = repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produccion no encontrada con id: " + id));

        if (production.getStatus() == ProductionStatus.CANCELADO) {
            throw new IllegalStateException("La produccion fue cancelada concurrentemente");
        }

        ProductionStatus previousStatus = production.getStatus();
        validateTransition(previousStatus, ProductionStatus.CONFIRMADO);

        production.setStatus(ProductionStatus.CONFIRMADO);
        Production saved = repository.saveAndFlush(production);

        taskService.markConfirmed(id);
        publishStatusChangedEventAndOutbox(saved, previousStatus, actor);

        return saved;
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

