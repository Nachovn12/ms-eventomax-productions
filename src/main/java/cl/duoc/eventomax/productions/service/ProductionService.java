package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.ProductionRequestDTO;
import cl.duoc.eventomax.productions.dto.ProductionResponseDTO;
import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.ProductionRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.LocalDateTime;
import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.integration.catalog.dto.CatalogReservationResponseDTO;
import cl.duoc.eventomax.productions.integration.catalog.exception.InventoryReservationException;
import cl.duoc.eventomax.productions.model.InventoryReservationTask;
import cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent;

@Service
public class ProductionService {

    private final ProductionRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final CatalogClient catalogClient;
    private final cl.duoc.eventomax.productions.repository.OutboxEventRepository outboxEventRepository;
    private final cl.duoc.eventomax.productions.repository.InventoryReservationTaskRepository inventoryReservationTaskRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public ProductionService(ProductionRepository repository,
                             ApplicationEventPublisher eventPublisher,
                             CatalogClient catalogClient,
                             cl.duoc.eventomax.productions.repository.OutboxEventRepository outboxEventRepository,
                             cl.duoc.eventomax.productions.repository.InventoryReservationTaskRepository inventoryReservationTaskRepository) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.catalogClient = catalogClient;
        this.outboxEventRepository = outboxEventRepository;
        this.inventoryReservationTaskRepository = inventoryReservationTaskRepository;
        this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Transactional
    public ProductionResponseDTO createProduction(ProductionRequestDTO request, String actor) {

        Production production = new Production();

        production.setOrganizerId(request.organizerId());
        production.setName(request.name());
        production.setScheduledAt(request.scheduledAt());
        production.setLocation(request.location());
        production.setStatus(ProductionStatus.SOLICITADO);

        Production saved = repository.save(production);

        try {
            java.time.Instant now = java.time.Instant.now();
            var trace = cl.duoc.eventomax.productions.tracing.TraceContext.current();
            cl.duoc.eventomax.productions.event.ProductionCreatedEvent payload = new cl.duoc.eventomax.productions.event.ProductionCreatedEvent(
                    saved.getId(),
                    saved.getOrganizerId(),
                    saved.getName(),
                    saved.getStatus().name(),
                    saved.getScheduledAt(),
                    saved.getLocation(),
                    now
            );

            cl.duoc.eventomax.productions.event.KafkaEventEnvelope<cl.duoc.eventomax.productions.event.ProductionCreatedEvent> envelope =
                    new cl.duoc.eventomax.productions.event.KafkaEventEnvelope<>(
                            java.util.UUID.randomUUID(),
                            "ProductionCreated",
                            now,
                            trace.traceId(),
                            trace.correlationId(),
                            actor,
                            payload
                    );

            String jsonPayload = objectMapper.writeValueAsString(envelope);
            cl.duoc.eventomax.productions.model.OutboxEvent outboxEvent = new cl.duoc.eventomax.productions.model.OutboxEvent(
                    "Production",
                    String.valueOf(saved.getId()),
                    "ProductionCreated",
                    jsonPayload
            );
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            throw new RuntimeException("Error al guardar evento en outbox", e);
        }

        return toResponseDTO(saved);
    }


    @Transactional(readOnly = true)
    public Optional<ProductionResponseDTO> getProductionById(Long id) {

        return repository.findById(id)
                .map(this::toResponseDTO);

    }


    @Transactional(readOnly = true)
    public List<ProductionResponseDTO> getAllProductions() {

        return repository.findAll()
                .stream()
                .map(this::toResponseDTO)
                .toList();

    }


    @Transactional(readOnly = true)
    public List<ProductionResponseDTO> getFilteredProductions(
            String statusStr, LocalDateTime from, LocalDateTime to) {

        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("La fecha desde no puede ser posterior a la fecha hasta");
        }

        ProductionStatus status = null;
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                status = ProductionStatus.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Estado de producción no válido: " + statusStr);
            }
        }

        return repository.findByFilters(status, from, to)
                .stream()
                .map(this::toResponseDTO)
                .toList();

    }


    @Transactional
    public ProductionResponseDTO updateStatus(Long id, ProductionStatusUpdateDTO request, String actor) {

        Production production = repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Produccion no encontrada con id: " + id));

        ProductionStatus previousStatus = production.getStatus();
        validateTransition(previousStatus, request.status());

        if (request.status() == ProductionStatus.CONFIRMADO) {
            if (request.items() == null || request.items().isEmpty()) {
                throw new IllegalArgumentException("Se requieren ítems de inventario para confirmar una producción");
            }

            String serializedItems;
            try {
                serializedItems = objectMapper.writeValueAsString(request.items());
            } catch (Exception e) {
                serializedItems = "[]";
            }
            final String itemsJson = serializedItems;

            InventoryReservationTask task = inventoryReservationTaskRepository.findByProductionId(production.getId())
                    .orElseGet(() -> new InventoryReservationTask(production.getId(), itemsJson, InventoryReservationTask.STATUS_PENDING));
            task.setItemsJson(itemsJson);
            task.setStatus(InventoryReservationTask.STATUS_PENDING);
            inventoryReservationTaskRepository.save(task);

            try {
                catalogClient.reserveInventory(production.getId(), request.items());
                task.setStatus(InventoryReservationTask.STATUS_CONFIRMED);
                inventoryReservationTaskRepository.save(task);
            } catch (InventoryReservationException e) {
                task.setStatus(InventoryReservationTask.STATUS_FAILED);
                task.setLastError(e.getMessage());
                inventoryReservationTaskRepository.save(task);
                throw e;
            } catch (Exception e) {
                try {
                    List<CatalogReservationResponseDTO> reservations = catalogClient.getReservationsByProduction(production.getId());
                    boolean hasActive = reservations != null && reservations.stream()
                            .anyMatch(r -> "RESERVED".equalsIgnoreCase(r.status()));
                    if (hasActive) {
                        task.setStatus(InventoryReservationTask.STATUS_CONFIRMED);
                        inventoryReservationTaskRepository.save(task);
                    } else {
                        task.setStatus(InventoryReservationTask.STATUS_FAILED);
                        task.setLastError(e.getMessage());
                        inventoryReservationTaskRepository.save(task);
                        throw e;
                    }
                } catch (Exception checkEx) {
                    task.setStatus(InventoryReservationTask.STATUS_COMPENSATING);
                    task.setLastError(e.getMessage());
                    inventoryReservationTaskRepository.save(task);
                    try {
                        catalogClient.compensateInventory(production.getId());
                        task.setStatus(InventoryReservationTask.STATUS_COMPENSATED);
                        inventoryReservationTaskRepository.save(task);
                    } catch (Exception ignored) {
                    }
                    throw e;
                }
            }
        }

        production.setStatus(request.status());
        Production updated;
        try {
            updated = repository.saveAndFlush(production);

            java.time.Instant now = java.time.Instant.now();
            var trace = cl.duoc.eventomax.productions.tracing.TraceContext.current();
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

            cl.duoc.eventomax.productions.event.KafkaEventEnvelope<ProductionStatusChangedEvent> envelope =
                    new cl.duoc.eventomax.productions.event.KafkaEventEnvelope<>(
                            java.util.UUID.randomUUID(),
                            "ProductionStatusChanged",
                            now,
                            trace.traceId(),
                            trace.correlationId(),
                            actor,
                            statusEvent
                    );

            String jsonPayload = objectMapper.writeValueAsString(envelope);
            cl.duoc.eventomax.productions.model.OutboxEvent outboxEvent = new cl.duoc.eventomax.productions.model.OutboxEvent(
                    "Production",
                    String.valueOf(updated.getId()),
                    "ProductionStatusChanged",
                    jsonPayload
            );
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            if (request.status() == ProductionStatus.CONFIRMADO) {
                try {
                    catalogClient.compensateInventory(production.getId());
                    inventoryReservationTaskRepository.findByProductionId(production.getId())
                            .ifPresent(t -> {
                                t.setStatus(InventoryReservationTask.STATUS_COMPENSATED);
                                inventoryReservationTaskRepository.save(t);
                            });
                } catch (Exception compEx) {
                    inventoryReservationTaskRepository.findByProductionId(production.getId())
                            .ifPresent(t -> {
                                t.setStatus(InventoryReservationTask.STATUS_COMPENSATING);
                                t.setLastError(compEx.getMessage());
                                inventoryReservationTaskRepository.save(t);
                            });
                }
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Error al actualizar estado de produccion", e);
        }

        return toResponseDTO(updated);
    }


    // -------------------------------------------------------------------------
    // Transitions
    // -------------------------------------------------------------------------

    /**
     * Allowed transitions map.
     * Key   = current status
     * Value = set of statuses the current one can transition TO
     *
     * Main rule (README): EN_MONTAJE requires CONFIRMADO as previous state.
     */
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

    private void validateTransition(ProductionStatus current, ProductionStatus next) {

        Set<ProductionStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(
                current, EnumSet.noneOf(ProductionStatus.class));

        if (!allowed.contains(next)) {
            throw new InvalidTransitionException(
                    "Transicion invalida: " + current + " -> " + next
                    + ". Transiciones permitidas desde " + current + ": " + allowed);
        }
    }


    // -------------------------------------------------------------------------
    // Mapper
    // -------------------------------------------------------------------------

    private ProductionResponseDTO toResponseDTO(Production production) {

        return new ProductionResponseDTO(
                production.getId(),
                production.getOrganizerId(),
                production.getName(),
                production.getScheduledAt(),
                production.getLocation(),
                production.getStatus().name(),
                production.getCreatedAt(),
                production.getUpdatedAt()
        );
    }
}
