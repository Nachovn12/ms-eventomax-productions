package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.ContractedServiceDto;
import cl.duoc.eventomax.productions.dto.ProductionReservationItemDTO;
import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent;
import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.integration.catalog.dto.CatalogServiceDTO;
import cl.duoc.eventomax.productions.integration.catalog.exception.CatalogUnavailableException;
import cl.duoc.eventomax.productions.model.InventoryReservationTask;
import cl.duoc.eventomax.productions.model.OutboxEvent;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionContractedService;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.InventoryReservationTaskRepository;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import cl.duoc.eventomax.productions.repository.ProductionContractedServiceRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ProductionServiceContractedServicesTest {

    @Mock
    private ProductionRepository repository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private CatalogClient catalogClient;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private InventoryReservationTaskRepository inventoryReservationTaskRepository;

    @Mock
    private InventoryReservationTaskService taskService;

    @Mock
    private ProductionContractedServiceRepository contractedServiceRepository;

    private ProductionStateCoordinator stateCoordinator;
    private ProductionService service;

    private Production production;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

        production = new Production();
        production.setId(100L);
        production.setName("Festival de Musica");
        production.setOrganizerId("org-test");
        production.setLocation("Estadio");
        production.setScheduledAt(LocalDateTime.now().plusDays(10));
        production.setStatus(ProductionStatus.SOLICITADO);

        stateCoordinator = new ProductionStateCoordinator(
                repository, taskService, eventPublisher, outboxEventRepository,
                contractedServiceRepository, objectMapper, catalogClient
        );

        service = new ProductionService(
                repository, eventPublisher, catalogClient, outboxEventRepository,
                inventoryReservationTaskRepository, taskService, stateCoordinator
        );
    }

    @Test
    @DisplayName("Debe validar y contratar servicios activos en Catalog, persistir en BD y publicar evento con services")
    void updateStatus_withValidServices_success() throws Exception {
        when(repository.findForUpdate(100L)).thenReturn(Optional.of(production));
        when(repository.saveAndFlush(any(Production.class))).thenAnswer(i -> i.getArguments()[0]);

        CatalogServiceDTO svc1 = new CatalogServiceDTO(10L, "Catering Premium", "Comida", new BigDecimal("50000.00"), true);
        CatalogServiceDTO svc2 = new CatalogServiceDTO(20L, "Seguridad VIP", "Guardias", new BigDecimal("30000.00"), true);

        when(catalogClient.getServiceById(10L)).thenReturn(Optional.of(svc1));
        when(catalogClient.getServiceById(20L)).thenReturn(Optional.of(svc2));

        InventoryReservationTask task = new InventoryReservationTask(100L, "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_PROCESSING);
        task.setReservationGeneration(1L);
        when(taskService.initOrUpdateIntent(eq(100L), anyString(), anyString())).thenReturn(task);
        when(taskService.getTaskByProductionId(100L)).thenReturn(Optional.empty(), Optional.of(task));
        when(taskService.markConfirmed(100L, 1L)).thenReturn(1);

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(
                ProductionStatus.CONFIRMADO,
                List.of(new ProductionReservationItemDTO(1L, 2)),
                List.of(10L, 20L)
        );

        var response = service.updateStatus(100L, request, "admin-user");

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("CONFIRMADO");

        // Verificación de llamadas remotas a Catalog
        verify(catalogClient).getServiceById(10L);
        verify(catalogClient).getServiceById(20L);
        verify(catalogClient).reserveInventory(eq(100L), anyList(), eq(1L));

        // Verificación de persistencia en tabla production_services
        ArgumentCaptor<ProductionContractedService> contractedCaptor = ArgumentCaptor.forClass(ProductionContractedService.class);
        verify(contractedServiceRepository, times(2)).save(contractedCaptor.capture());
        List<ProductionContractedService> savedServices = contractedCaptor.getAllValues();
        assertThat(savedServices).hasSize(2);
        assertThat(savedServices.get(0).getServiceId()).isEqualTo(10L);
        assertThat(savedServices.get(0).getServiceName()).isEqualTo("Catering Premium");
        assertThat(savedServices.get(0).getUnitPrice()).isEqualByComparingTo("50000.00");
        assertThat(savedServices.get(1).getServiceId()).isEqualTo(20L);

        // Verificación de evento Outbox
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEvent outboxEvent = outboxCaptor.getValue();
        assertThat(outboxEvent.getEventType()).isEqualTo("ProductionStatusChanged");
        JsonNode payloadRoot = objectMapper.readTree(outboxEvent.getPayload());
        assertThat(payloadRoot.get("type").asText()).isEqualTo("ProductionStatusChanged");
        JsonNode servicesNode = payloadRoot.get("payload").get("services");
        assertThat(servicesNode.isArray()).isTrue();
        assertThat(servicesNode.size()).isEqualTo(2);
        assertThat(servicesNode.get(0).get("serviceId").asLong()).isEqualTo(10L);
        assertThat(servicesNode.get(0).get("serviceName").asText()).isEqualTo("Catering Premium");
    }

    @Test
    @DisplayName("Debe rechazar IDs de servicios negativos o cero con IllegalArgumentException")
    void updateStatus_withNegativeOrZeroServiceId_throwsIllegalArgumentException() {
        when(repository.findForUpdate(100L)).thenReturn(Optional.of(production));

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(
                ProductionStatus.CONFIRMADO,
                List.of(new ProductionReservationItemDTO(1L, 2)),
                List.of(-5L)
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateStatus(100L, request, "admin-user"));
        assertThat(ex.getMessage()).contains("número positivo");
        verifyNoInteractions(contractedServiceRepository);
    }

    @Test
    @DisplayName("Debe rechazar IDs de servicios duplicados con IllegalArgumentException")
    void updateStatus_withDuplicateServiceIds_throwsIllegalArgumentException() {
        when(repository.findForUpdate(100L)).thenReturn(Optional.of(production));

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(
                ProductionStatus.CONFIRMADO,
                List.of(new ProductionReservationItemDTO(1L, 2)),
                List.of(10L, 10L)
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateStatus(100L, request, "admin-user"));
        assertThat(ex.getMessage()).contains("duplicados");
        verifyNoInteractions(contractedServiceRepository);
    }

    @Test
    @DisplayName("Debe rechazar cuando el servicio no existe en Catalog (404)")
    void updateStatus_serviceNotFoundInCatalog_throwsIllegalArgumentException() {
        when(repository.findForUpdate(100L)).thenReturn(Optional.of(production));
        when(catalogClient.getServiceById(999L)).thenReturn(Optional.empty());

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(
                ProductionStatus.CONFIRMADO,
                List.of(new ProductionReservationItemDTO(1L, 2)),
                List.of(999L)
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateStatus(100L, request, "admin-user"));
        assertThat(ex.getMessage()).contains("no existe en el catálogo");
        verifyNoInteractions(contractedServiceRepository);
    }

    @Test
    @DisplayName("Debe rechazar cuando el servicio existe pero no está activo en Catalog")
    void updateStatus_serviceInactiveInCatalog_throwsIllegalArgumentException() {
        when(repository.findForUpdate(100L)).thenReturn(Optional.of(production));
        CatalogServiceDTO inactiveSvc = new CatalogServiceDTO(15L, "Luces", "Desc", new BigDecimal("100.00"), false);
        when(catalogClient.getServiceById(15L)).thenReturn(Optional.of(inactiveSvc));

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(
                ProductionStatus.CONFIRMADO,
                List.of(new ProductionReservationItemDTO(1L, 2)),
                List.of(15L)
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateStatus(100L, request, "admin-user"));
        assertThat(ex.getMessage()).contains("no está activo");
        verifyNoInteractions(contractedServiceRepository);
    }

    @Test
    @DisplayName("Debe propagar 503 cuando Catalog no está disponible al consultar servicios (sin defaults silenciosos)")
    void updateStatus_catalogUnavailable_propagatesException() {
        when(repository.findForUpdate(100L)).thenReturn(Optional.of(production));
        when(catalogClient.getServiceById(10L)).thenThrow(new CatalogUnavailableException("Catalog down"));

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(
                ProductionStatus.CONFIRMADO,
                List.of(new ProductionReservationItemDTO(1L, 2)),
                List.of(10L)
        );

        assertThrows(CatalogUnavailableException.class, () -> service.updateStatus(100L, request, "admin-user"));
        verifyNoInteractions(contractedServiceRepository);
        verify(catalogClient, never()).reserveInventory(anyLong(), anyList(), any());
    }

    @Test
    @DisplayName("Replay idempotente con mismos servicios y equipos no consulta a Catalog ni duplica")
    void updateStatus_idempotentReplayWithSameServices_returnsProductionWithoutDuplicating() {
        production.setStatus(ProductionStatus.CONFIRMADO);
        when(repository.findById(100L)).thenReturn(Optional.of(production));
        when(repository.findForUpdate(100L)).thenReturn(Optional.of(production));

        InventoryReservationTask task = new InventoryReservationTask(100L, "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_CONFIRMED);
        task.setReservationGeneration(1L);
        task.setServicesJson("[{\"serviceId\":10,\"serviceName\":\"Catering\",\"unitPrice\":50000.00}]");

        when(taskService.getTaskByProductionId(100L)).thenReturn(Optional.of(task));
        when(taskService.areItemsCompatible(anyString(), anyString())).thenReturn(true);
        when(taskService.areServicesCompatibleWithIds(anyString(), anyList())).thenReturn(true);
        when(taskService.areServicesCompatible(anyString(), anyString())).thenReturn(true);

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(
                ProductionStatus.CONFIRMADO,
                List.of(new ProductionReservationItemDTO(1L, 2)),
                List.of(10L)
        );

        var response = service.updateStatus(100L, request, "admin-user");

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("CONFIRMADO");
        // H02: Replay ya confirmado no debe invocar Catalog bajo ninguna circunstancia
        verify(catalogClient, never()).getServiceById(anyLong());
        verify(catalogClient, never()).reserveInventory(anyLong(), anyList(), any());
        verifyNoInteractions(contractedServiceRepository);
    }

    @Test
    @DisplayName("H02: Replay idempotente es exitoso incluso si Catalog está temporalmente caído")
    void updateStatus_idempotentReplay_succeedsEvenIfCatalogIsUnavailable() {
        production.setStatus(ProductionStatus.CONFIRMADO);
        when(repository.findById(100L)).thenReturn(Optional.of(production));

        InventoryReservationTask task = new InventoryReservationTask(100L, "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_CONFIRMED);
        task.setReservationGeneration(1L);
        task.setServicesJson("[{\"serviceId\":10,\"serviceName\":\"Catering\",\"unitPrice\":50000.00}]");

        when(taskService.getTaskByProductionId(100L)).thenReturn(Optional.of(task));
        when(taskService.areItemsCompatible(anyString(), anyString())).thenReturn(true);
        when(taskService.areServicesCompatibleWithIds(anyString(), anyList())).thenReturn(true);

        // Si Catalog intentara ser invocado, lanzaría CatalogUnavailableException
        when(catalogClient.getServiceById(anyLong())).thenThrow(new CatalogUnavailableException("Catalog caído"));

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(
                ProductionStatus.CONFIRMADO,
                List.of(new ProductionReservationItemDTO(1L, 2)),
                List.of(10L)
        );

        var response = service.updateStatus(100L, request, "admin-user");

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("CONFIRMADO");
        verify(catalogClient, never()).getServiceById(anyLong());
    }

    @Test
    @DisplayName("H02: Conflicto si la producción ya fue confirmada y se reenvía con servicios diferentes (sin invocar Catalog)")
    void updateStatus_replayWithDifferentServices_throwsConflict() {
        production.setStatus(ProductionStatus.CONFIRMADO);
        when(repository.findById(100L)).thenReturn(Optional.of(production));

        InventoryReservationTask task = new InventoryReservationTask(100L, "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_CONFIRMED);
        task.setReservationGeneration(1L);
        task.setServicesJson("[{\"serviceId\":10,\"serviceName\":\"Catering\",\"unitPrice\":50000.00}]");

        when(taskService.getTaskByProductionId(100L)).thenReturn(Optional.of(task));
        when(taskService.areItemsCompatible(anyString(), anyString())).thenReturn(true);
        when(taskService.areServicesCompatibleWithIds(anyString(), anyList())).thenReturn(false);

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(
                ProductionStatus.CONFIRMADO,
                List.of(new ProductionReservationItemDTO(1L, 2)),
                List.of(20L)
        );

        InvalidTransitionException ex = assertThrows(InvalidTransitionException.class,
                () -> service.updateStatus(100L, request, "admin-user"));
        assertThat(ex.getMessage()).contains("otra seleccion de servicios o equipamiento");
        // H02: No debe consultar Catalog
        verify(catalogClient, never()).getServiceById(anyLong());
        verifyNoInteractions(contractedServiceRepository);
    }

    @Test
    @DisplayName("H02: Confirmar producción cancelada lanza 409 sin consultar Catalog")
    void updateStatus_cancelledProduction_throwsConflictWithoutQueryingCatalog() {
        production.setStatus(ProductionStatus.CANCELADO);
        when(repository.findById(100L)).thenReturn(Optional.of(production));

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(
                ProductionStatus.CONFIRMADO,
                List.of(new ProductionReservationItemDTO(1L, 2)),
                List.of(10L)
        );

        InvalidTransitionException ex = assertThrows(InvalidTransitionException.class,
                () -> service.updateStatus(100L, request, "admin-user"));
        assertThat(ex.getMessage()).contains("No se puede confirmar una produccion en estado CANCELADO");
        verify(catalogClient, never()).getServiceById(anyLong());
    }

    @Test
    @DisplayName("Fallo al registrar en Outbox revierte estado a SOLICITADO y lanza RuntimeException")
    void updateStatus_outboxFailure_revertsProductionState() {
        when(repository.findForUpdate(100L)).thenReturn(Optional.of(production));
        when(repository.saveAndFlush(any(Production.class))).thenAnswer(i -> i.getArguments()[0]);

        CatalogServiceDTO svc1 = new CatalogServiceDTO(10L, "Catering Premium", "Comida", new BigDecimal("50000.00"), true);
        when(catalogClient.getServiceById(10L)).thenReturn(Optional.of(svc1));

        InventoryReservationTask task = new InventoryReservationTask(100L, "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_PROCESSING);
        task.setReservationGeneration(1L);
        when(taskService.initOrUpdateIntent(eq(100L), anyString(), anyString())).thenReturn(task);
        when(taskService.getTaskByProductionId(100L)).thenReturn(Optional.empty(), Optional.of(task));
        when(taskService.markConfirmed(100L, 1L)).thenReturn(1);

        // Simular fallo en outboxEventRepository
        doThrow(new RuntimeException("Outbox DB error")).when(outboxEventRepository).save(any(OutboxEvent.class));

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(
                ProductionStatus.CONFIRMADO,
                List.of(new ProductionReservationItemDTO(1L, 2)),
                List.of(10L)
        );

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.updateStatus(100L, request, "admin-user"));

        assertThat(ex.getMessage()).contains("Outbox");
        assertThat(production.getStatus()).isEqualTo(ProductionStatus.SOLICITADO);
    }

    @Test
    @DisplayName("H02: Carrera concurrente donde otra solicitud confirma mientras se validaba Catalog: stateCoordinator resuelve claim idempotente bajo bloqueo")
    void updateStatus_concurrentConfirmationRace_handledByStateCoordinatorUnderLock() {
        // En el pre-check inicial la producción aparece SOLICITADO
        Production prodSolicitado = new Production();
        prodSolicitado.setId(100L);
        prodSolicitado.setName("Festival Concurrente");
        prodSolicitado.setStatus(ProductionStatus.SOLICITADO);

        // Pero al adquirir el row lock en beginConfirmation, otra transacción concurrente ya confirmó
        Production prodConfirmado = new Production();
        prodConfirmado.setId(100L);
        prodConfirmado.setName("Festival Concurrente");
        prodConfirmado.setStatus(ProductionStatus.CONFIRMADO);

        when(repository.findById(100L)).thenReturn(Optional.of(prodSolicitado));
        when(repository.findForUpdate(100L)).thenReturn(Optional.of(prodConfirmado));

        CatalogServiceDTO svc1 = new CatalogServiceDTO(10L, "Catering", "Comida", new BigDecimal("50000.00"), true);
        when(catalogClient.getServiceById(10L)).thenReturn(Optional.of(svc1));

        InventoryReservationTask task = new InventoryReservationTask(100L, "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_CONFIRMED);
        task.setReservationGeneration(1L);
        task.setServicesJson("[{\"serviceId\":10,\"serviceName\":\"Catering\",\"unitPrice\":50000.00}]");

        when(taskService.getTaskByProductionId(100L)).thenReturn(Optional.of(task));
        when(taskService.areItemsCompatible(anyString(), anyString())).thenReturn(true);
        when(taskService.areServicesCompatible(anyString(), anyString())).thenReturn(true);

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(
                ProductionStatus.CONFIRMADO,
                List.of(new ProductionReservationItemDTO(1L, 2)),
                List.of(10L)
        );

        var response = service.updateStatus(100L, request, "admin-user");

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("CONFIRMADO");
        // No debe re-ejecutar reserva en Catalog ni duplicar en contractedServiceRepository
        verify(catalogClient, never()).reserveInventory(anyLong(), anyList(), any());
        verifyNoInteractions(contractedServiceRepository);
    }
}
