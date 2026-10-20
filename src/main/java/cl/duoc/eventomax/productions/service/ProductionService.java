package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.ContractedServiceDto;
import cl.duoc.eventomax.productions.dto.ProductionRequestDTO;
import cl.duoc.eventomax.productions.dto.ProductionReservationItemDTO;
import cl.duoc.eventomax.productions.dto.ProductionResponseDTO;
import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.event.KafkaEventEnvelope;
import cl.duoc.eventomax.productions.event.ProductionCreatedEvent;
import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.integration.catalog.dto.CatalogReservationResponseDTO;
import cl.duoc.eventomax.productions.integration.catalog.exception.InventoryReservationException;
import cl.duoc.eventomax.productions.messaging.rabbitmq.ProductionQuoteCommandListener;
import cl.duoc.eventomax.productions.model.OutboxEvent;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.InventoryReservationTaskRepository;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import cl.duoc.eventomax.productions.repository.ProductionContractedServiceRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import cl.duoc.eventomax.productions.tracing.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductionService {

    private static final Logger log = LoggerFactory.getLogger(ProductionService.class);

    private final ProductionRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final CatalogClient catalogClient;
    private final OutboxEventRepository outboxEventRepository;
    private final InventoryReservationTaskRepository inventoryReservationTaskRepository;
    private final InventoryReservationTaskService taskService;
    private final ProductionStateCoordinator stateCoordinator;
    private final ProductionContractedServiceRepository contractedServiceRepository;
    private final ProductionQuoteCommandListener quoteCommandListener;
    private final ObjectMapper objectMapper;

    public ProductionService(
            ProductionRepository repository,
            ApplicationEventPublisher eventPublisher,
            CatalogClient catalogClient,
            OutboxEventRepository outboxEventRepository,
            InventoryReservationTaskRepository inventoryReservationTaskRepository,
            InventoryReservationTaskService taskService,
            ProductionStateCoordinator stateCoordinator) {
        this(repository, eventPublisher, catalogClient, outboxEventRepository,
                inventoryReservationTaskRepository, taskService, stateCoordinator, null, null);
    }

    @Autowired
    public ProductionService(
            ProductionRepository repository,
            ApplicationEventPublisher eventPublisher,
            CatalogClient catalogClient,
            OutboxEventRepository outboxEventRepository,
            InventoryReservationTaskRepository inventoryReservationTaskRepository,
            InventoryReservationTaskService taskService,
            ProductionStateCoordinator stateCoordinator,
            @Autowired(required = false) ProductionContractedServiceRepository contractedServiceRepository,
            @Autowired(required = false) ProductionQuoteCommandListener quoteCommandListener) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.catalogClient = catalogClient;
        this.outboxEventRepository = outboxEventRepository;
        this.inventoryReservationTaskRepository = inventoryReservationTaskRepository;
        this.taskService = taskService;
        this.stateCoordinator = stateCoordinator;
        this.contractedServiceRepository = contractedServiceRepository;
        this.quoteCommandListener = quoteCommandListener;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Transactional
    public ProductionResponseDTO createProduction(ProductionRequestDTO request, String actor) {

        Production production = new Production();

        String effectiveOrganizerId = (request.organizerId() != null && !request.organizerId().isBlank())
                ? request.organizerId().trim()
                : actor;

        production.setOrganizerId(effectiveOrganizerId);
        production.setName(request.name());
        production.setScheduledAt(request.scheduledAt());
        production.setLocation(request.location());
        production.setStatus(ProductionStatus.SOLICITADO);

        Production saved = repository.save(production);

        try {
            Instant now = Instant.now();
            var trace = TraceContext.current();
            ProductionCreatedEvent payload = new ProductionCreatedEvent(
                    saved.getId(),
                    saved.getOrganizerId(),
                    saved.getName(),
                    saved.getStatus().name(),
                    saved.getScheduledAt(),
                    saved.getLocation(),
                    now
            );

            KafkaEventEnvelope<ProductionCreatedEvent> envelope =
                    new KafkaEventEnvelope<>(
                            UUID.randomUUID(),
                            "ProductionCreated",
                            now,
                            trace.traceId(),
                            trace.correlationId(),
                            actor,
                            payload
                    );

            String jsonPayload = objectMapper.writeValueAsString(envelope);
            OutboxEvent outboxEvent = new OutboxEvent(
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
    public List<ContractedServiceDto> getContractedServices(Long productionId) {
        if (repository.findById(productionId).isEmpty()) {
            throw new ResourceNotFoundException("Produccion no encontrada con id: " + productionId);
        }
        if (contractedServiceRepository == null) {
            return List.of();
        }
        return contractedServiceRepository.findByProductionId(productionId).stream()
                .map(s -> new ContractedServiceDto(
                        s.getServiceId(),
                        s.getServiceName(),
                        s.getUnitPrice()))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean republishQuoteCommand(Long productionId, String actor) {
        Production production = repository.findById(productionId)
                .orElseThrow(() -> new ResourceNotFoundException("Produccion no encontrada con id: " + productionId));
        if (production.getStatus() != ProductionStatus.CERRADO) {
            throw new InvalidTransitionException(
                    "Solo se puede republicar el comando de acta PDF cuando la produccion esta en estado CERRADO (actual: "
                            + production.getStatus() + ")");
        }
        if (quoteCommandListener == null) {
            throw new IllegalStateException("Listener de publicacion de acta PDF no disponible");
        }
        return quoteCommandListener.republishQuoteCommand(productionId, actor);
    }

    @Transactional(readOnly = true)
    public List<ProductionResponseDTO> getAllProductions() {
        return repository.findAll()
                .stream()
                .map(this::toResponseDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProductionResponseDTO> getAllProductionsForOwner(String ownerId) {
        return repository.findByOrganizerId(ownerId)
                .stream()
                .map(this::toResponseDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProductionResponseDTO> getFilteredProductions(
            String statusStr, LocalDateTime from, LocalDateTime to) {
        return getFilteredProductionsForOwner(statusStr, from, to, null);
    }

    @Transactional(readOnly = true)
    public List<ProductionResponseDTO> getFilteredProductionsForOwner(
            String statusStr, LocalDateTime from, LocalDateTime to, String ownerId) {

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

        return repository.findByFilters(status, from, to, ownerId)
                .stream()
                .map(this::toResponseDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProductionResponseDTO> getAllProductionsForOrganizers(java.util.Collection<String> organizerIds) {
        if (organizerIds == null || organizerIds.isEmpty()) {
            return List.of();
        }
        return repository.findByOrganizerIdIn(organizerIds)
                .stream()
                .map(this::toResponseDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProductionResponseDTO> getFilteredProductionsForOrganizers(
            String statusStr, LocalDateTime from, LocalDateTime to, java.util.Collection<String> organizerIds) {

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

        return repository.findByFilters(status, from, to, organizerIds)
                .stream()
                .map(this::toResponseDTO)
                .toList();
    }

    public ProductionResponseDTO updateStatus(Long id, ProductionStatusUpdateDTO request, String actor) {
        if (request.status() == ProductionStatus.CONFIRMADO) {
            return handleConfirmation(id, request, actor);
        } else if (request.status() == ProductionStatus.CANCELADO) {
            return handleCancellation(id, actor);
        } else {
            return executeStandardStatusUpdate(id, request.status(), actor);
        }
    }

    private ProductionResponseDTO handleConfirmation(Long id, ProductionStatusUpdateDTO request, String actor) {
        if (request.items() == null || request.items().isEmpty()) {
            stateCoordinator.validateCanConfirm(id);
            throw new IllegalArgumentException("Se requieren ítems de inventario para confirmar una producción");
        }

        if (request.serviceIds() != null && !request.serviceIds().isEmpty()) {
            for (Long sId : request.serviceIds()) {
                if (sId == null || sId <= 0) {
                    stateCoordinator.validateCanConfirm(id);
                    throw new IllegalArgumentException("ID de servicio inválido: debe ser un número positivo");
                }
            }
            Set<Long> uniqueIds = new HashSet<>(request.serviceIds());
            if (uniqueIds.size() != request.serviceIds().size()) {
                stateCoordinator.validateCanConfirm(id);
                throw new IllegalArgumentException("No se permiten IDs de servicios duplicados");
            }
        }

        List<ProductionReservationItemDTO> sortedItems = request.items().stream()
                .sorted(Comparator.comparing(ProductionReservationItemDTO::equipmentId))
                .toList();

        String itemsJson;
        try {
            itemsJson = objectMapper.writeValueAsString(sortedItems);
        } catch (Exception e) {
            itemsJson = "[]";
        }

        // Fast-path / Idempotencia: si la producción ya está confirmada o cancelada, resolver o rechazar sin contactar a Catalog
        Optional<ProductionStateCoordinator.ConfirmationClaim> fastClaim =
                stateCoordinator.checkFastReplayOrInvalid(id, itemsJson, request.serviceIds());
        if (fastClaim.isPresent() && fastClaim.get().alreadyConfirmed()) {
            return toResponseDTO(fastClaim.get().production());
        }

        // Para nuevas confirmaciones: validación remota contra Catalog (fuera de transacciones y sin locks abiertos)
        List<cl.duoc.eventomax.productions.dto.ContractedServiceDto> contractedServices = new ArrayList<>();
        if (request.serviceIds() != null && !request.serviceIds().isEmpty()) {
            Set<Long> uniqueIds = new HashSet<>(request.serviceIds());
            for (Long sId : uniqueIds.stream().sorted().toList()) {
                Optional<cl.duoc.eventomax.productions.integration.catalog.dto.CatalogServiceDTO> catalogSvcOpt =
                        catalogClient.getServiceById(sId);
                if (catalogSvcOpt.isEmpty()) {
                    stateCoordinator.validateCanConfirm(id);
                    throw new IllegalArgumentException("El servicio con ID " + sId + " no existe en el catálogo");
                }
                var catalogSvc = catalogSvcOpt.get();
                if (catalogSvc.active() == null || !catalogSvc.active()) {
                    stateCoordinator.validateCanConfirm(id);
                    throw new IllegalArgumentException("El servicio con ID " + sId + " no está activo");
                }
                contractedServices.add(new cl.duoc.eventomax.productions.dto.ContractedServiceDto(
                        catalogSvc.id(),
                        catalogSvc.name(),
                        catalogSvc.rate()
                ));
            }
        }

        String servicesJson;
        try {
            servicesJson = objectMapper.writeValueAsString(contractedServices);
        } catch (Exception e) {
            servicesJson = "[]";
        }

        // 1. Coordinar intención de confirmación de forma atómica durable bajo row lock (vía REQUIRES_NEW)
        ProductionStateCoordinator.ConfirmationClaim claim = stateCoordinator.beginConfirmation(id, itemsJson, servicesJson);
        if (claim.alreadyConfirmed()) {
            return toResponseDTO(claim.production());
        }

        Long generation = claim.generation();

        // 2. Llamada HTTP a Catalog (sin transacciones de BD ni locks abiertos)
        try {
            if (generation != null) {
                catalogClient.reserveInventory(id, sortedItems, generation);
            } else {
                catalogClient.reserveInventory(id, sortedItems);
            }
        } catch (InventoryReservationException e) {
            taskService.markFailed(id, generation, e.getMessage());
            throw e;
        } catch (Exception e) {
            // Reconciliación ante timeout o falla de red
            try {
                List<CatalogReservationResponseDTO> reservations = catalogClient.getReservationsByProduction(id);
                boolean hasActive = reservations != null && reservations.stream()
                        .anyMatch(r -> "RESERVED".equalsIgnoreCase(r.status()));
                if (hasActive) {
                    return completeConfirmationWithCompensationOnFailure(id, actor, generation, contractedServices);
                } else {
                    taskService.markFailed(id, generation, e.getMessage());
                    throw e;
                }
            } catch (Exception checkEx) {
                if (stateCoordinator.tryBeginCompensation(id, generation, "Error de comunicación con Catalog: " + e.getMessage())) {
                    try {
                        if (generation != null) {
                            catalogClient.compensateInventory(id, generation);
                        } else {
                            catalogClient.compensateInventory(id);
                        }
                        stateCoordinator.completeCompensation(id, generation);
                    } catch (Exception ignored) {
                    }
                }
                throw e;
            }
        }

        // 3. Completar confirmación local en una nueva transacción vía stateCoordinator
        return completeConfirmationWithCompensationOnFailure(id, actor, generation, contractedServices);
    }

    private ProductionResponseDTO completeConfirmationWithCompensationOnFailure(
            Long id, String actor, Long generation, List<cl.duoc.eventomax.productions.dto.ContractedServiceDto> contractedServices) {
        try {
            Production saved = stateCoordinator.completeConfirmation(id, actor, generation, contractedServices);
            return toResponseDTO(saved);
        } catch (Exception e) {
            boolean canCompensate = stateCoordinator.tryBeginCompensation(
                    id, generation, "Fallo al confirmar localmente: " + e.getMessage());
            if (canCompensate) {
                try {
                    if (generation != null) {
                        catalogClient.compensateInventory(id, generation);
                    } else {
                        catalogClient.compensateInventory(id);
                    }
                    stateCoordinator.completeCompensation(id, generation);
                } catch (Exception compEx) {
                    log.warn("Compensación inmediata falló para producción {}, el worker reintentará", id, compEx);
                }
            } else {
                log.info("Compensación omitida para producción {} porque el estado actual o la generación no permiten compensar", id, e);
            }
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        }
    }

    private ProductionResponseDTO completeConfirmationWithCompensationOnFailure(Long id, String actor, Long generation) {
        return completeConfirmationWithCompensationOnFailure(id, actor, generation, null);
    }

    public ProductionResponseDTO completeConfirmation(Long id, String actor, Long generation) {
        return completeConfirmationWithCompensationOnFailure(id, actor, generation, null);
    }

    public ProductionResponseDTO completeConfirmation(Long id, String actor, Long generation, List<cl.duoc.eventomax.productions.dto.ContractedServiceDto> contractedServices) {
        return completeConfirmationWithCompensationOnFailure(id, actor, generation, contractedServices);
    }

    public ProductionResponseDTO completeConfirmation(Long id, String actor) {
        return completeConfirmationWithCompensationOnFailure(id, actor, null);
    }

    private ProductionResponseDTO handleCancellation(Long id, String actor) {
        // 1. Commit atómico local en REQUIRES_NEW vía stateCoordinator
        Production saved = stateCoordinator.executeLocalCancellation(id, actor);

        // 2. Compensación externa en Catalog post-commit con reintento seguro
        Optional<cl.duoc.eventomax.productions.model.InventoryReservationTask> taskOpt = taskService.getTaskByProductionId(id);
        Long generation = taskOpt.map(cl.duoc.eventomax.productions.model.InventoryReservationTask::getReservationGeneration).orElse(null);
        try {
            if (generation != null) {
                catalogClient.compensateInventory(id, generation);
            } else {
                catalogClient.compensateInventory(id);
            }
            stateCoordinator.completeCompensation(id, generation);
        } catch (Exception e) {
            log.warn("Post-commit compensation to Catalog failed for production {}, worker will retry", id, e);
        }
        return toResponseDTO(saved);
    }

    public ProductionResponseDTO executeStandardStatusUpdate(Long id, ProductionStatus nextStatus, String actor) {
        Production saved = stateCoordinator.executeStandardStatusUpdate(id, nextStatus, actor);
        return toResponseDTO(saved);
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
