package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.ProductionReservationItemDTO;
import cl.duoc.eventomax.productions.dto.ProductionResponseDTO;
import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.integration.catalog.dto.CatalogReservationResponseDTO;
import cl.duoc.eventomax.productions.integration.catalog.exception.CatalogUnavailableException;
import cl.duoc.eventomax.productions.model.InventoryReservationTask;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.InventoryReservationTaskRepository;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class InventoryReservationReliabilityReproductionTest {

    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"));

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("spring.datasource.driver-class-name", DB::getDriverClassName);
        registry.add("eventomax.reservation-recovery.initial-delay-ms", () -> "3600000");
        registry.add("eventomax.reservation-recovery.delay-ms", () -> "3600000");
    }

    @Autowired

    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;



    @Autowired
    ProductionService productionService;

    @Autowired
    ProductionRepository productionRepository;

    @Autowired
    InventoryReservationTaskRepository taskRepository;

    @MockitoSpyBean
    InventoryReservationTaskService taskService;

    @MockitoSpyBean
    OutboxEventRepository outboxEventRepository;

    @Autowired
    InventoryReservationRecoveryWorker recoveryWorker;

    @Autowired
    ProductionStateCoordinator stateCoordinator;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    CatalogClient catalogClient;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @BeforeEach
    void clean() {
        reset(outboxEventRepository);
        reset(taskService);
        taskRepository.deleteAll();
        outboxEventRepository.deleteAll();
        productionRepository.deleteAll();
        reset(catalogClient);
    }

    private Production createProduction(ProductionStatus status) {
        Production p = new Production();
        p.setOrganizerId("test-organizer");
        p.setName("Festival Concurrencia");
        p.setLocation("Estadio Nacional");
        p.setScheduledAt(LocalDateTime.now().plusDays(10));
        p.setStatus(status);
        return productionRepository.saveAndFlush(p);
    }

    @Test
    @DisplayName("1. Catalog reserva exitosamente pero fallo en Productions preserva la intención durable y el worker compensa")
    void testRollbackAfterCatalogReservation_persistsDurableTask_andWorkerRecoversStock() {
        Production p = createProduction(ProductionStatus.SOLICITADO);

        doNothing().when(catalogClient).reserveInventory(eq(p.getId()), any());

        // Con el diseño desacoplado P0-B2, la intención se persiste de manera durable antes de llamar a Catalog
        taskService.initOrUpdateIntent(p.getId(), "[{\"equipmentId\":1,\"quantity\":5}]");

        // Verificamos que la tarea QUEDA PERSISTIDA en BD a pesar de cualquier interrupción posterior
        Optional<InventoryReservationTask> taskOpt = taskRepository.findByProductionId(p.getId());
        assertTrue(taskOpt.isPresent(), "La tarea de reserva quedó persistida de forma durable en BD");
        assertEquals(InventoryReservationTask.STATUS_PROCESSING, taskOpt.get().getStatus());

        // Simulamos que el recovery worker corre para recuperar la producción no confirmada
        when(catalogClient.getReservationsByProduction(p.getId())).thenReturn(List.of(
                new CatalogReservationResponseDTO(1L, 1L, "Audio", 5, "RESERVED")
        ));
        lenient().doNothing().when(catalogClient).compensateInventory(eq(p.getId()), any());
        lenient().doNothing().when(catalogClient).compensateInventory(p.getId());

        recoveryWorker.processTaskRecovery(taskOpt.get());

        // Se verifica que Catalog compensó el inventario huérfano
        verify(catalogClient, times(1)).compensateInventory(eq(p.getId()), eq(1L));
        InventoryReservationTask recovered = taskRepository.findByProductionId(p.getId()).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_COMPENSATED, recovered.getStatus());
    }

    @Test
    @DisplayName("2. Cancelación desde CONFIRMADO ejecuta compensación en Catalog y persiste Outbox atómicamente")
    void testCancellationFromConfirmado_atomicallyCompensatesInventoryAndPersistsOutbox() {
        Production p = createProduction(ProductionStatus.CONFIRMADO);
        InventoryReservationTask initialTask = new InventoryReservationTask(p.getId(), "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_CONFIRMED);
        taskRepository.saveAndFlush(initialTask);

        lenient().doNothing().when(catalogClient).compensateInventory(eq(p.getId()), any());
        lenient().doNothing().when(catalogClient).compensateInventory(p.getId());

        // El usuario cancela la producción
        productionService.updateStatus(p.getId(), new ProductionStatusUpdateDTO(
                ProductionStatus.CANCELADO,
                null
        ), "test-actor");

        // 1. Verificamos que la producción está cancelada
        Production current = productionRepository.findById(p.getId()).orElseThrow();
        assertEquals(ProductionStatus.CANCELADO, current.getStatus());

        // 2. Verificamos compensación en Catalog invocada
        verify(catalogClient, times(1)).compensateInventory(eq(p.getId()), any());

        // 3. Verificamos que la tarea terminó en COMPENSATED
        InventoryReservationTask task = taskRepository.findByProductionId(p.getId()).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_COMPENSATED, task.getStatus());

        // 4. Verificamos evento en Outbox
        var outboxEvents = outboxEventRepository.findAll();
        boolean hasCancellationEvent = outboxEvents.stream().anyMatch(e ->
                e.getAggregateId().equals(String.valueOf(p.getId())) &&
                e.getEventType().equals("ProductionStatusChanged")
        );
        assertTrue(hasCancellationEvent, "El evento de cambio de estado a CANCELADO quedó registrado en el Outbox");
    }

    @Test
    @DisplayName("3. Dos workers simultáneos compitiendo por la misma tarea: reclamo atómico garantiza una sola compensación")
    void testTwoConcurrentWorkers_competingForSameTask_atomicClaimPreventsDuplicateCompensations() throws Exception {
        Production p = createProduction(ProductionStatus.SOLICITADO);

        InventoryReservationTask task = new InventoryReservationTask(p.getId(), "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_PENDING);
        task = taskRepository.saveAndFlush(task);

        when(catalogClient.getReservationsByProduction(p.getId())).thenReturn(List.of(
                new CatalogReservationResponseDTO(1L, 1L, "Audio", 2, "RESERVED")
        ));
        lenient().doNothing().when(catalogClient).compensateInventory(eq(p.getId()), any());
        lenient().doNothing().when(catalogClient).compensateInventory(p.getId());

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);

        AtomicInteger claimSuccesses = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        final InventoryReservationTask initialTask = task;
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    boolean claimed = taskService.claimTaskForRecovery(initialTask);
                    if (claimed) {
                        claimSuccesses.incrementAndGet();
                        recoveryWorker.processTaskRecovery(initialTask);
                    }
                } catch (Exception e) {
                    // ignore
                }
            }));
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, claimSuccesses.get(), "Exactamente uno de los dos workers debe ganar el reclamo atómico");
        verify(catalogClient, times(1)).compensateInventory(eq(p.getId()), any());
    }

    @Test
    @DisplayName("4. Pod restart: tarea persistida en BD es recuperada exitosamente tras reinicio")
    void testPodRestart_recoversPersistedTask() {
        Production p = createProduction(ProductionStatus.SOLICITADO);

        InventoryReservationTask task = new InventoryReservationTask(p.getId(), "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_PROCESSING);
        taskRepository.saveAndFlush(task);

        when(catalogClient.getReservationsByProduction(p.getId())).thenReturn(List.of(
                new CatalogReservationResponseDTO(1L, 1L, "Audio", 2, "RESERVED")
        ));
        lenient().doNothing().when(catalogClient).compensateInventory(eq(p.getId()), any());
        lenient().doNothing().when(catalogClient).compensateInventory(p.getId());

        recoveryWorker.processTaskRecovery(task);

        verify(catalogClient, times(1)).compensateInventory(eq(p.getId()), any());
        InventoryReservationTask updatedTask = taskRepository.findByProductionId(p.getId()).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_COMPENSATED, updatedTask.getStatus());
    }

    @Test
    @DisplayName("5. Tarea PROCESSING abandonada con lease vencido es recuperada por el worker")
    void testAbandonedProcessingTaskWithExpiredLease_isClaimedByWorker() {
        Production p = createProduction(ProductionStatus.SOLICITADO);

        InventoryReservationTask task = new InventoryReservationTask(p.getId(), "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_PROCESSING);
        task = taskRepository.saveAndFlush(task);

        LocalDateTime cutoff = LocalDateTime.now().plusSeconds(1);
        List<InventoryReservationTask> claimable = taskRepository.findClaimableTasks(cutoff);
        assertTrue(claimable.stream().anyMatch(t -> t.getProductionId().equals(p.getId())), "La tarea PROCESSING abandonada debe ser seleccionable");

        when(catalogClient.getReservationsByProduction(p.getId())).thenReturn(List.of(
                new CatalogReservationResponseDTO(1L, 1L, "Audio", 2, "RESERVED")
        ));
        lenient().doNothing().when(catalogClient).compensateInventory(eq(p.getId()), any());
        lenient().doNothing().when(catalogClient).compensateInventory(p.getId());

        boolean claimed = taskService.claimTaskForRecovery(task);
        assertTrue(claimed, "El worker debe poder reclamar la tarea PROCESSING con lease expirado");
        recoveryWorker.processTaskRecovery(task);

        verify(catalogClient, times(1)).compensateInventory(eq(p.getId()), any());
        InventoryReservationTask updatedTask = taskRepository.findByProductionId(p.getId()).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_COMPENSATED, updatedTask.getStatus());
    }

    @Test
    @DisplayName("6. Lease activo no vencido previene que un worker reclame una tarea en curso")
    void testActiveLeaseNotExpired_preventsWorkerClaim() {
        Production p = createProduction(ProductionStatus.SOLICITADO);

        InventoryReservationTask task = new InventoryReservationTask(p.getId(), "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_PROCESSING);
        taskRepository.saveAndFlush(task);

        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(15);
        List<InventoryReservationTask> claimable = taskRepository.findClaimableTasks(cutoff);
        assertFalse(claimable.stream().anyMatch(t -> t.getProductionId().equals(p.getId())), "Una tarea con lease vigente no debe ser reclamable");
    }

    @Test
    @DisplayName("7. Timeout durante reserva en Catalog reconcilia y completa confirmación si la reserva existe")
    void testTimeoutDuringReservation_reconcilesAndCompletesConfirmation() {
        Production p = createProduction(ProductionStatus.SOLICITADO);

        lenient().doThrow(new CatalogUnavailableException("Connection timed out"))
                .when(catalogClient).reserveInventory(eq(p.getId()), any(), any());
        lenient().doThrow(new CatalogUnavailableException("Connection timed out"))
                .when(catalogClient).reserveInventory(eq(p.getId()), any());

        when(catalogClient.getReservationsByProduction(p.getId())).thenReturn(List.of(
                new CatalogReservationResponseDTO(1L, 1L, "Audio", 2, "RESERVED")
        ));

        var result = productionService.updateStatus(p.getId(), new ProductionStatusUpdateDTO(
                ProductionStatus.CONFIRMADO,
                List.of(new ProductionReservationItemDTO(1L, 2))
        ), "test-actor");

        assertEquals("CONFIRMADO", result.status());
        Production current = productionRepository.findById(p.getId()).orElseThrow();
        assertEquals(ProductionStatus.CONFIRMADO, current.getStatus());
        InventoryReservationTask task = taskRepository.findByProductionId(p.getId()).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_CONFIRMED, task.getStatus());
    }

    @Test
    @DisplayName("8. Cancelación concurrente durante reserva en curso aborta la confirmación y compensa Catalog")
    void testCancellationDuringInFlightReservation_abortsConfirmationAndCompensatesCatalog() {
        Production p = createProduction(ProductionStatus.SOLICITADO);

        doNothing().when(catalogClient).reserveInventory(eq(p.getId()), any());
        lenient().doNothing().when(catalogClient).compensateInventory(eq(p.getId()), any());
        lenient().doNothing().when(catalogClient).compensateInventory(p.getId());

        // Reserva en curso (intención durable persistida)
        InventoryReservationTask task = taskService.initOrUpdateIntent(p.getId(), "[{\"equipmentId\":1,\"quantity\":2}]");
        Long generation = task.getReservationGeneration();

        // Simulamos que antes de completeConfirmation, otra transacción canceló la producción
        p.setStatus(ProductionStatus.CANCELADO);
        productionRepository.saveAndFlush(p);

        assertThrows(IllegalStateException.class, () -> {
            productionService.completeConfirmation(p.getId(), "test-actor", generation);
        });

        verify(catalogClient, times(1)).compensateInventory(eq(p.getId()), any());
        InventoryReservationTask updatedTask = taskRepository.findByProductionId(p.getId()).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_COMPENSATED, updatedTask.getStatus());
    }

    @Test
    @DisplayName("9. Compensaciones repetidas son seguras e idempotentes")
    void testRepeatedCompensations_areSafeAndIdempotent() {
        Production p = createProduction(ProductionStatus.CANCELADO);
        InventoryReservationTask task = new InventoryReservationTask(p.getId(), "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_COMPENSATING);
        taskRepository.saveAndFlush(task);

        lenient().doNothing().when(catalogClient).compensateInventory(eq(p.getId()), any());
        lenient().doNothing().when(catalogClient).compensateInventory(p.getId());

        recoveryWorker.processTaskRecovery(task);
        verify(catalogClient, times(1)).compensateInventory(eq(p.getId()), any());

        recoveryWorker.processTaskRecovery(task);
        verify(catalogClient, times(2)).compensateInventory(eq(p.getId()), any());

        InventoryReservationTask finalTask = taskRepository.findByProductionId(p.getId()).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_COMPENSATED, finalTask.getStatus());
    }

    @Test
    @DisplayName("10. Rollback atómico en confirmación: si Outbox falla, Producción y Tarea revierten juntas")
    void testAtomicRollbackOnConfirmation_whenOutboxFails_productionAndTaskRollbackTogether() {
        Production p = createProduction(ProductionStatus.SOLICITADO);
        InventoryReservationTask task = new InventoryReservationTask(p.getId(), "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_PROCESSING);
        taskRepository.saveAndFlush(task);

        doThrow(new RuntimeException("Simulated Outbox Failure"))
                .when(outboxEventRepository).save(any());

        assertThrows(RuntimeException.class, () -> {
            stateCoordinator.completeConfirmation(p.getId(), "actor-test", task.getReservationGeneration());
        });

        // 1. Producción NO quedó en CONFIRMADO (revirtió a SOLICITADO)
        Production pDb = productionRepository.findById(p.getId()).orElseThrow();
        assertEquals(ProductionStatus.SOLICITADO, pDb.getStatus(), "Producción debe revertir a SOLICITADO");

        // 2. Tarea NO quedó en CONFIRMED (revirtió atómicamente a PROCESSING)
        InventoryReservationTask tDb = taskRepository.findByProductionId(p.getId()).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_PROCESSING, tDb.getStatus(), "Tarea debe revertir atómicamente a PROCESSING");
    }

    @Test
    @DisplayName("11. Rollback atómico en cancelación: si Outbox falla, Producción y Tarea revierten juntas")
    void testAtomicRollbackOnCancellation_whenOutboxFails_productionAndTaskRollbackTogether() {
        Production p = createProduction(ProductionStatus.CONFIRMADO);
        InventoryReservationTask task = new InventoryReservationTask(p.getId(), "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_CONFIRMED);
        taskRepository.saveAndFlush(task);

        doThrow(new RuntimeException("Simulated Outbox Failure"))
                .when(outboxEventRepository).save(any());

        assertThrows(RuntimeException.class, () -> {
            productionService.updateStatus(p.getId(), new ProductionStatusUpdateDTO(ProductionStatus.CANCELADO, null), "actor-test");
        });

        // 1. Producción NO quedó en CANCELADO (revirtió a CONFIRMADO)
        Production pDb = productionRepository.findById(p.getId()).orElseThrow();
        assertEquals(ProductionStatus.CONFIRMADO, pDb.getStatus(), "Producción debe revertir a CONFIRMADO");

        // 2. Tarea NO quedó en COMPENSATING (revirtió atómicamente a CONFIRMED)
        InventoryReservationTask tDb = taskRepository.findByProductionId(p.getId()).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_CONFIRMED, tDb.getStatus(), "Tarea debe revertir atómicamente a CONFIRMED");
    }

    @Test
    @DisplayName("12. Carrera A/B en updateStatus(CONFIRMADO): el fallo o replay de B tras el commit de A no debe compensar la reserva ganadora de A")
    void testConcurrentConfirmationRace_threadBCompletingReserveAfterThreadACommits_mustNotCompensateWinnerStock() throws Exception {
        Production p = createProduction(ProductionStatus.SOLICITADO);
        Long prodId = p.getId();

        // Simulador explícito con estado de Catalog (separado de PostgreSQL real de Productions)
        final Object catalogLock = new Object();
        final AtomicInteger catalogAvailableStock = new AtomicInteger(10);
        final AtomicInteger catalogReservedStock = new AtomicInteger(0);
        final AtomicReference<String> catalogReservationState = new AtomicReference<>("NONE");
        final AtomicReference<List<ProductionReservationItemDTO>> catalogReservedItems = new AtomicReference<>(List.of());

        // Coordinación determinista de papeles A (confirma primero) y B (sale de reserveInventory tras commit de A)
        final ThreadLocal<String> requestRole = new ThreadLocal<>();
        final AtomicBoolean threadAReachedReserve = new AtomicBoolean(false);
        final CountDownLatch threadAReservedInCatalog = new CountDownLatch(1);
        final CountDownLatch threadBReservedInCatalog = new CountDownLatch(1);
        final CountDownLatch threadACommittedConfirmation = new CountDownLatch(1);

        org.mockito.stubbing.Answer<Void> answerReserve = invocation -> {
            @SuppressWarnings("unchecked")
            List<ProductionReservationItemDTO> requested = invocation.getArgument(1);
            String role = requestRole.get();
            assertNotNull(role, "El papel del hilo (A o B) debe estar asignado explícitamente");

            synchronized (catalogLock) {
                if ("NONE".equals(catalogReservationState.get())) {
                    int qty = requested.stream().mapToInt(ProductionReservationItemDTO::quantity).sum();
                    catalogAvailableStock.addAndGet(-qty);
                    catalogReservedStock.addAndGet(qty);
                    catalogReservedItems.set(List.copyOf(requested));
                    catalogReservationState.set("RESERVED");
                } else if ("RESERVED".equals(catalogReservationState.get())) {
                    // Replay idempotente en Catalog para el mismo productionId y mismos ítems
                    assertEquals(catalogReservedItems.get(), requested,
                            "El replay idempotente en Catalog simulado requiere el mismo conjunto de ítems");
                } else {
                    fail("Estado inesperado en Catalog simulado al reservar: " + catalogReservationState.get());
                }
            }

            if ("A".equals(role)) {
                threadAReachedReserve.set(true);
                // A ya ejecutó initOrUpdateIntent (commit en BD) y reservó en Catalog; habilita el inicio de B
                threadAReservedInCatalog.countDown();
                assertTrue(
                        threadBReservedInCatalog.await(10, TimeUnit.SECONDS),
                        "Timeout esperando a que B pase validateCanConfirm/initOrUpdateIntent y entre a reserveInventory"
                );
            } else if ("B".equals(role)) {
                // B ya pasó validateCanConfirm e initOrUpdateIntent y reservó idempotentemente; libera a A para confirmar
                threadBReservedInCatalog.countDown();
                assertTrue(
                        threadACommittedConfirmation.await(10, TimeUnit.SECONDS),
                        "Timeout esperando a que A confirme la producción en PostgreSQL antes de que B salga de reserveInventory"
                );
            } else {
                fail("Papel inesperado en reserveInventory: " + role);
            }
            return null;
        };
        lenient().doAnswer(answerReserve).when(catalogClient).reserveInventory(eq(prodId), any(), any());
        lenient().doAnswer(answerReserve).when(catalogClient).reserveInventory(eq(prodId), any());

        org.mockito.stubbing.Answer<Void> answerCompensate = invocation -> {
            synchronized (catalogLock) {
                if ("RESERVED".equals(catalogReservationState.get())) {
                    int qty = catalogReservedItems.get().stream()
                            .mapToInt(ProductionReservationItemDTO::quantity)
                            .sum();
                    catalogAvailableStock.addAndGet(qty);
                    catalogReservedStock.addAndGet(-qty);
                    catalogReservationState.set("COMPENSATED");
                }
            }
            return null;
        };
        lenient().doAnswer(answerCompensate).when(catalogClient).compensateInventory(eq(prodId), any());
        lenient().doAnswer(answerCompensate).when(catalogClient).compensateInventory(prodId);

        ProductionStatusUpdateDTO request = new ProductionStatusUpdateDTO(
                ProductionStatus.CONFIRMADO,
                List.of(new ProductionReservationItemDTO(100L, 2))
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        ProductionResponseDTO responseA = null;
        Throwable errorA = null;
        ProductionResponseDTO responseB = null;
        Throwable errorB = null;

        try {
            Future<ProductionResponseDTO> futureA = executor.submit(() -> {
                requestRole.set("A");
                try {
                    return productionService.updateStatus(prodId, request, "actor-A");
                } finally {
                    requestRole.remove();
                    threadAReservedInCatalog.countDown();
                    threadACommittedConfirmation.countDown();
                }
            });

            Future<ProductionResponseDTO> futureB = executor.submit(() -> {
                requestRole.set("B");
                try {
                    assertTrue(
                            threadAReservedInCatalog.await(10, TimeUnit.SECONDS),
                            "Timeout esperando a que A cree la intención inicial antes de iniciar B"
                    );
                    assertTrue(
                            threadAReachedReserve.get(),
                            "El hilo A falló antes de alcanzar reserveInventory"
                    );
                    return productionService.updateStatus(prodId, request, "actor-B");
                } finally {
                    requestRole.remove();
                    threadBReservedInCatalog.countDown();
                }
            });

            try {
                responseA = futureA.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                errorA = e.getCause() != null ? e.getCause() : e;
            }

            try {
                responseB = futureB.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                errorB = e.getCause() != null ? e.getCause() : e;
            }
        } finally {
            threadAReservedInCatalog.countDown();
            threadBReservedInCatalog.countDown();
            threadACommittedConfirmation.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        final ProductionResponseDTO finalResponseA = responseA;
        final Throwable finalErrorA = errorA;
        final Throwable finalErrorB = errorB;

        Production dbProd = productionRepository.findById(prodId).orElseThrow();
        InventoryReservationTask dbTask = taskRepository.findByProductionId(prodId).orElseThrow();
        long confirmedOutboxCount = outboxEventRepository.findAll().stream()
                .filter(e -> String.valueOf(prodId).equals(e.getAggregateId())
                        && "ProductionStatusChanged".equals(e.getEventType()))
                .count();

        assertAll("Poscondiciones de integridad entre PostgreSQL real y Catalog simulado",
                () -> assertNull(finalErrorA, () -> "La solicitud ganadora A no debe fallar, pero lanzó: " + finalErrorA),
                () -> assertNotNull(finalResponseA, "La solicitud A debe retornar respuesta"),
                () -> assertEquals("CONFIRMADO", finalResponseA != null ? finalResponseA.status() : null, "A debe confirmar la producción"),
                () -> assertEquals(ProductionStatus.CONFIRMADO, dbProd.getStatus(), "PostgreSQL: la producción debe permanecer en CONFIRMADO"),
                () -> assertEquals(1L, confirmedOutboxCount, "PostgreSQL: debe existir exactamente 1 evento ProductionStatusChanged en Outbox"),
                () -> assertEquals("RESERVED", catalogReservationState.get(),
                        () -> "Catalog simulado: la reserva ganadora de A fue compensada indebidamente tras el fallo de B (" + finalErrorB + ")"),
                () -> assertEquals(8, catalogAvailableStock.get(), "Catalog simulado: el stock disponible debe permanecer descontado en 8"),
                () -> assertEquals(2, catalogReservedStock.get(), "Catalog simulado: el stock reservado debe permanecer en 2"),
                () -> assertEquals(InventoryReservationTask.STATUS_CONFIRMED, dbTask.getStatus(),
                        "PostgreSQL: la tarea durable debe permanecer en CONFIRMED y no ser sobrescrita a COMPENSATED"),
                () -> verify(catalogClient, never()).compensateInventory(eq(prodId), any()),
                () -> verify(catalogClient, never()).compensateInventory(prodId)
        );
    }

    @Test
    @DisplayName("13. Recovery worker real invocation (recoverStuckTasks): claimTask increment blocks compensation in buggy unseparated state")
    void testRecoverStuckTasks_realInvocation_claimIncrementMustNotBlockCompensation() {
        Production p = createProduction(ProductionStatus.SOLICITADO);
        Long prodId = p.getId();

        InventoryReservationTask task = taskService.initOrUpdateIntent(prodId, "[{\"equipmentId\":1,\"quantity\":2}]");
        assertEquals(1, task.getAttemptCount());

        jdbcTemplate.update("UPDATE inventory_reservation_tasks SET updated_at = NOW() - INTERVAL '30 seconds' WHERE id = ?", task.getId());

        when(catalogClient.getReservationsByProduction(prodId)).thenReturn(List.of(
                new CatalogReservationResponseDTO(1L, 1L, "Audio", 2, "RESERVED")
        ));
        lenient().doNothing().when(catalogClient).compensateInventory(eq(prodId), any());
        lenient().doNothing().when(catalogClient).compensateInventory(prodId);

        recoveryWorker.recoverStuckTasks();

        verify(catalogClient, times(1)).compensateInventory(eq(prodId), any());
        InventoryReservationTask finalTask = taskRepository.findByProductionId(prodId).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_COMPENSATED, finalTask.getStatus());
    }

    @Test
    @DisplayName("14. Confirmaciones simultaneas con equipamiento incompatible: solo una intencion gana, la perdedora es rechazada sin sobreescribir ni compensar")
    void testConcurrentConfirmations_withIncompatibleItems_onlyOneWins_noOverwrite() throws Exception {
        Production p = createProduction(ProductionStatus.SOLICITADO);
        Long prodId = p.getId();

        List<ProductionReservationItemDTO> itemsA = List.of(new ProductionReservationItemDTO(101L, 2));
        List<ProductionReservationItemDTO> itemsB = List.of(new ProductionReservationItemDTO(202L, 5));

        ProductionStatusUpdateDTO requestA = new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO, itemsA);
        ProductionStatusUpdateDTO requestB = new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO, itemsB);

        final Object catalogLock = new Object();
        final AtomicReference<List<ProductionReservationItemDTO>> catalogReservedItems = new AtomicReference<>(null);
        final CountDownLatch threadAInReserve = new CountDownLatch(1);
        final CountDownLatch threadBFinished = new CountDownLatch(1);

        org.mockito.stubbing.Answer<Void> answerReserve = invocation -> {
            @SuppressWarnings("unchecked")
            List<ProductionReservationItemDTO> requested = invocation.getArgument(1);
            synchronized (catalogLock) {
                if (catalogReservedItems.get() == null) {
                    catalogReservedItems.set(List.copyOf(requested));
                }
            }
            threadAInReserve.countDown();
            assertTrue(threadBFinished.await(10, TimeUnit.SECONDS), "Timeout esperando a que B intente confirmar");
            return null;
        };

        lenient().doAnswer(answerReserve).when(catalogClient).reserveInventory(eq(prodId), eq(itemsA), any());
        lenient().doAnswer(answerReserve).when(catalogClient).reserveInventory(eq(prodId), eq(itemsA));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<ProductionResponseDTO> futureA = null;
        Future<ProductionResponseDTO> futureB = null;

        try {
            futureA = executor.submit(() -> productionService.updateStatus(prodId, requestA, "actor-A"));

            futureB = executor.submit(() -> {
                try {
                    assertTrue(threadAInReserve.await(10, TimeUnit.SECONDS), "Timeout esperando a que A cree la intencion");
                    return productionService.updateStatus(prodId, requestB, "actor-B");
                } finally {
                    threadBFinished.countDown();
                }
            });

            ProductionResponseDTO resA = futureA.get(15, TimeUnit.SECONDS);
            assertEquals("CONFIRMADO", resA.status());

            Throwable errB = null;
            try {
                futureB.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException ee) {
                errB = ee.getCause();
            }
            assertNotNull(errB, "La solicitud concurrente B con items incompatibles debe ser rechazada");

            Production dbProd = productionRepository.findById(prodId).orElseThrow();
            assertEquals(ProductionStatus.CONFIRMADO, dbProd.getStatus());

            InventoryReservationTask dbTask = taskRepository.findByProductionId(prodId).orElseThrow();
            assertEquals(InventoryReservationTask.STATUS_CONFIRMED, dbTask.getStatus());
            assertTrue(taskService.areItemsCompatible(dbTask.getItemsJson(), "[{\"equipmentId\":101,\"quantity\":2}]"),
                    "Los items en BD deben pertenecer al ganador A y no haber sido sobreescritos por B");

            assertEquals(itemsA, catalogReservedItems.get(), "Catalog debe almacenar exclusivamente los items ganadores de A");

            long outboxCount = outboxEventRepository.findAll().stream()
                    .filter(e -> String.valueOf(prodId).equals(e.getAggregateId())
                            && "ProductionStatusChanged".equals(e.getEventType()))
                    .count();
            assertEquals(1L, outboxCount, "Outbox debe registrar exactamente 1 evento de transicion a CONFIRMADO");

            verify(catalogClient, never()).compensateInventory(eq(prodId), any());
            verify(catalogClient, never()).compensateInventory(prodId);
        } finally {
            threadAInReserve.countDown();
            threadBFinished.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("15. Carrera F01: Respuesta tardia de generacion 1 tras compensacion e inicio de generacion 2 no debe confirmar ni sobreescribir la generacion 2")
    void testStaleGeneration1_afterCompensationAndGeneration2Started_mustNotConfirmOrOverwrite() throws Exception {
        Production p = createProduction(ProductionStatus.SOLICITADO);
        Long prodId = p.getId();

        List<ProductionReservationItemDTO> itemsA = List.of(new ProductionReservationItemDTO(101L, 2));
        List<ProductionReservationItemDTO> itemsB = List.of(new ProductionReservationItemDTO(202L, 4));

        ProductionStatusUpdateDTO requestA = new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO, itemsA);

        CountDownLatch aInReserve = new CountDownLatch(1);
        CountDownLatch aRelease = new CountDownLatch(1);

        // Hilo A llama a reserveInventory con gen 1L. Bloqueamos hasta que el worker compense y B inicie gen 2.
        lenient().doAnswer(inv -> {
            aInReserve.countDown();
            assertTrue(aRelease.await(10, TimeUnit.SECONDS), "Timeout esperando liberar respuesta demorada de A");
            return null;
        }).when(catalogClient).reserveInventory(eq(prodId), eq(itemsA), eq(1L));

        lenient().doNothing().when(catalogClient).compensateInventory(eq(prodId), any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<ProductionResponseDTO> futureA = null;

        try {
            // 1. Iniciar Petición A (generación 1)
            futureA = executor.submit(() -> productionService.updateStatus(prodId, requestA, "actor-A"));

            // Esperar a que A esté dentro de reserveInventory (tarea en PROCESSING con gen 1L)
            assertTrue(aInReserve.await(5, TimeUnit.SECONDS), "Timeout esperando que A inicie reserva en Catalog");

            InventoryReservationTask taskAfterA = taskRepository.findByProductionId(prodId).orElseThrow();
            assertEquals(1L, taskAfterA.getReservationGeneration());
            assertEquals(InventoryReservationTask.STATUS_PROCESSING, taskAfterA.getStatus());

            // 2. Simular que la tarea expira y el Recovery Worker compensa la generación 1
            jdbcTemplate.update("UPDATE inventory_reservation_tasks SET updated_at = NOW() - INTERVAL '30 seconds' WHERE id = ?", taskAfterA.getId());
            when(catalogClient.getReservationsByProduction(prodId)).thenReturn(List.of(
                    new CatalogReservationResponseDTO(1L, 101L, "Audio", 2, "RESERVED")
            ));

            recoveryWorker.recoverStuckTasks();

            InventoryReservationTask taskAfterRecovery = taskRepository.findByProductionId(prodId).orElseThrow();
            assertEquals(InventoryReservationTask.STATUS_COMPENSATED, taskAfterRecovery.getStatus());
            assertEquals(1L, taskAfterRecovery.getReservationGeneration());

            // 3. Petición B inicia generación 2: se registra la intención para B (PROCESSING, gen 2L)
            ProductionStateCoordinator.ConfirmationClaim claimB = stateCoordinator.beginConfirmation(
                    prodId, "[{\"equipmentId\":202,\"quantity\":4}]");
            assertEquals(2L, claimB.generation());

            InventoryReservationTask taskAfterB = taskRepository.findByProductionId(prodId).orElseThrow();
            assertEquals(2L, taskAfterB.getReservationGeneration());
            assertEquals(InventoryReservationTask.STATUS_PROCESSING, taskAfterB.getStatus());

            // 4. Ahora se libera la respuesta demorada de A (generación 1)
            aRelease.countDown();

            // 5. Verificar qué sucede con la Petición A:
            // Debe fallar porque la generación 1 ya fue compensada y superada por la generación 2
            Throwable errorA = null;
            try {
                futureA.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException ee) {
                errorA = ee.getCause();
            }

            // Exigencia del fix: A debe ser rechazada con excepción (no tener éxito)
            assertNotNull(errorA, "La confirmacion demorada de la generacion 1 DEBE fallar y no ser aceptada");

            // Estado persistido: la produccion debe permanecer en SOLICITADO (no confirmada por A)
            Production dbProd = productionRepository.findById(prodId).orElseThrow();
            assertEquals(ProductionStatus.SOLICITADO, dbProd.getStatus(),
                    "La produccion DEBE permanecer en SOLICITADO");

            // La tarea debe permanecer en PROCESSING para la generacion 2
            InventoryReservationTask dbTask = taskRepository.findByProductionId(prodId).orElseThrow();
            assertEquals(InventoryReservationTask.STATUS_PROCESSING, dbTask.getStatus(),
                    "La tarea debe permanecer en PROCESSING bajo la generacion 2");
            assertEquals(2L, dbTask.getReservationGeneration(), "La generacion vigente en la tarea debe ser 2");
            assertTrue(taskService.areItemsCompatible(dbTask.getItemsJson(), "[{\"equipmentId\":202,\"quantity\":4}]"),
                    "Los items de reserva de la generacion 2 deben permanecer intactos");

            // A no emite outbox
            long outboxCount = outboxEventRepository.findAll().stream()
                    .filter(e -> String.valueOf(prodId).equals(e.getAggregateId()))
                    .count();
            assertEquals(0L, outboxCount, "A no debe haber emitido ningun evento en Outbox");

            // A no emite Rabbit
            verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
            verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class), any(org.springframework.amqp.core.MessagePostProcessor.class));

            // A no llama a compensar generacion 2
            verify(catalogClient, never()).compensateInventory(eq(prodId), eq(2L));
            verify(catalogClient, times(1)).compensateInventory(eq(prodId), eq(1L));
        } finally {
            aInReserve.countDown();
            aRelease.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("16. Compensacion y fallo tardios de generacion anterior no alteran la generacion vigente, y replay de misma generacion es idempotente")
    void testStaleCompensationAndFailure_mustNotAlterCurrentGeneration() {
        Production p = createProduction(ProductionStatus.SOLICITADO);
        Long prodId = p.getId();

        // 1. Iniciar intención para generación 1
        InventoryReservationTask taskGen1 = taskService.initOrUpdateIntent(prodId, "[{\"equipmentId\":101,\"quantity\":2}]");
        assertEquals(1L, taskGen1.getReservationGeneration());
        assertEquals(InventoryReservationTask.STATUS_PROCESSING, taskGen1.getStatus());

        // 2. Avanzar a generación 2
        InventoryReservationTask taskGen2 = taskService.initOrUpdateIntent(prodId, "[{\"equipmentId\":101,\"quantity\":2}]");
        assertEquals(2L, taskGen2.getReservationGeneration());
        assertEquals(InventoryReservationTask.STATUS_PROCESSING, taskGen2.getStatus());

        // 3. Compensación tardía para generación 1: no debe afectar la generación 2
        boolean compRes = stateCoordinator.completeCompensation(prodId, 1L);
        assertFalse(compRes, "Compensación tardía para generación 1 debe ser rechazada/no modificar la generación 2");

        InventoryReservationTask dbTaskAfterComp = taskRepository.findByProductionId(prodId).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_PROCESSING, dbTaskAfterComp.getStatus());
        assertEquals(2L, dbTaskAfterComp.getReservationGeneration());

        // 4. Fallo tardío para generación 1: no debe alterar la tarea vigente
        int failRes = taskService.markFailed(prodId, 1L, "Error tardío gen 1");
        assertEquals(0, failRes, "Fallo tardío para generación 1 debe afectar 0 filas");

        InventoryReservationTask dbTaskAfterFail = taskRepository.findByProductionId(prodId).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_PROCESSING, dbTaskAfterFail.getStatus());
        assertEquals(2L, dbTaskAfterFail.getReservationGeneration());
        assertNull(dbTaskAfterFail.getLastError());

        // 5. Petición legacy (generation == null) frente a registro con generación 2: debe ser rechazada
        assertThrows(IllegalStateException.class, () -> stateCoordinator.completeConfirmation(prodId, "actor-legacy", null),
                "Petición legacy sin generación debe ser rechazada cuando el registro ya utiliza generaciones avanzadas");

        // 6. Confirmación legítima de la generación 2: debe tener éxito
        Production confirmedProd = stateCoordinator.completeConfirmation(prodId, "actor-valid", 2L);
        assertEquals(ProductionStatus.CONFIRMADO, confirmedProd.getStatus());

        InventoryReservationTask dbTaskFinal = taskRepository.findByProductionId(prodId).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_CONFIRMED, dbTaskFinal.getStatus());
        assertEquals(2L, dbTaskFinal.getReservationGeneration());

        // 7. Replay idempotente legítimo de la misma generación 2: debe retornar éxito
        Production replayProd = stateCoordinator.completeConfirmation(prodId, "actor-replay", 2L);
        assertEquals(ProductionStatus.CONFIRMADO, replayProd.getStatus());
    }

    @Test
    @DisplayName("17. Llamada con generacion nula contra registro con generacion 1 debe ser rechazada sin mutar estado")
    void testNullGenerationAgainstGeneration1Record_mustRejectWithoutMutatingState() {
        Production p = createProduction(ProductionStatus.SOLICITADO);
        Long prodId = p.getId();

        InventoryReservationTask task = taskService.initOrUpdateIntent(prodId, "[{\"equipmentId\":101,\"quantity\":2}]");
        assertEquals(1L, task.getReservationGeneration());
        assertEquals(InventoryReservationTask.STATUS_PROCESSING, task.getStatus());

        // Llamada con generation == null contra registro versionado con generación 1
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                stateCoordinator.completeConfirmation(prodId, "actor-legacy", null)
        );
        assertTrue(ex.getMessage().contains("Peticion sin generacion rechazada"));

        // Verificar que el estado en BD no fue mutado
        Production dbProd = productionRepository.findById(prodId).orElseThrow();
        assertEquals(ProductionStatus.SOLICITADO, dbProd.getStatus());

        InventoryReservationTask dbTask = taskRepository.findByProductionId(prodId).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_PROCESSING, dbTask.getStatus());
        assertEquals(1L, dbTask.getReservationGeneration());

        long outboxCount = outboxEventRepository.findAll().stream()
                .filter(e -> String.valueOf(prodId).equals(e.getAggregateId()))
                .count();
        assertEquals(0L, outboxCount);
    }

    @Test
    @DisplayName("18. Confirmacion sin tarea valida debe ser rechazada con produccion y outbox intactos")
    void testConfirmationWithoutTask_mustRejectWithProductionAndOutboxIntact() {
        Production p = createProduction(ProductionStatus.SOLICITADO);
        Long prodId = p.getId();

        // Sin tarea en la tabla inventory_reservation_tasks
        assertTrue(taskRepository.findByProductionId(prodId).isEmpty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                stateCoordinator.completeConfirmation(prodId, "actor", 1L)
        );
        assertTrue(ex.getMessage().contains("No existe una tarea o intencion de reserva valida"));

        // Producción y outbox intactos
        Production dbProd = productionRepository.findById(prodId).orElseThrow();
        assertEquals(ProductionStatus.SOLICITADO, dbProd.getStatus());

        long outboxCount = outboxEventRepository.findAll().stream()
                .filter(e -> String.valueOf(prodId).equals(e.getAggregateId()))
                .count();
        assertEquals(0L, outboxCount);
    }

    @Test
    @DisplayName("19. Actualizacion condicional con cero filas debe ejecutar la consulta, fallar sin falsos exitos y hacer rollback")
    void testConditionalUpdateZeroRows_mustFailWithoutFalseSuccess() {
        Production p = createProduction(ProductionStatus.SOLICITADO);
        Long prodId = p.getId();

        InventoryReservationTask task = taskService.initOrUpdateIntent(prodId, "[{\"equipmentId\":101,\"quantity\":2}]");
        assertEquals(1L, task.getReservationGeneration());
        assertEquals(InventoryReservationTask.STATUS_PROCESSING, task.getStatus());

        org.springframework.transaction.support.TransactionTemplate requiresNew =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Interceptamos markConfirmed: la lectura inicial en completeConfirmation valida exitosamente gen=1 y status=PROCESSING,
        // pero justo al momento de ejecutar el UPDATE condicional, una transacción concurrente independiente modificó la tarea en BD (status = FAILED) y commiteó.
        // De este modo, la consulta condicional UPDATE ... WHERE ... AND status IN ('PROCESSING', 'CONFIRMED')
        // realmente se ejecuta contra PostgreSQL y afecta 0 filas.
        doAnswer(invocation -> {
            requiresNew.executeWithoutResult(s -> {
                jdbcTemplate.update("UPDATE inventory_reservation_tasks SET status = 'FAILED' WHERE id = ?", task.getId());
            });
            return invocation.callRealMethod();
        }).when(taskService).markConfirmed(eq(prodId), eq(1L));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                stateCoordinator.completeConfirmation(prodId, "actor", 1L)
        );
        assertTrue(ex.getMessage().contains("cero filas actualizadas"));

        // Comprobamos que realmente se ejecutó esa operación de actualización condicional
        verify(taskService, times(1)).markConfirmed(eq(prodId), eq(1L));

        // Verificamos el estado desde una lectura independiente tras el rollback:
        // 1. La producción vuelve/permanece en SOLICITADO (rollback de la mutación a CONFIRMADO)
        String prodStatusDb = jdbcTemplate.queryForObject(
                "SELECT status FROM productions WHERE id = ?", String.class, prodId);
        assertEquals("SOLICITADO", prodStatusDb);

        Production dbProd = productionRepository.findById(prodId).orElseThrow();
        assertEquals(ProductionStatus.SOLICITADO, dbProd.getStatus());

        // 2. La tarea conserva su estado correcto (el FAILED de la transacción concurrente, sin sobreescritura a CONFIRMED)
        String taskStatusDb = jdbcTemplate.queryForObject(
                "SELECT status FROM inventory_reservation_tasks WHERE id = ?", String.class, task.getId());
        assertEquals("FAILED", taskStatusDb);

        InventoryReservationTask dbTask = taskRepository.findByProductionId(prodId).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_FAILED, dbTask.getStatus());
        assertEquals(1L, dbTask.getReservationGeneration());

        // 3. No aparecen eventos en outbox
        long outboxCount = outboxEventRepository.findAll().stream()
                .filter(e -> String.valueOf(prodId).equals(e.getAggregateId()))
                .count();
        assertEquals(0L, outboxCount);

        // 4. No aparecen comandos indebidos en RabbitMQ
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class), any(org.springframework.amqp.core.MessagePostProcessor.class));
    }

    @Test
    @DisplayName("20. Conflicto al iniciar compensacion desde ProductionService omite llamada a Catalog, con control positivo verificado")
    void testConflictStartingCompensation_mustNotTriggerExternalCatalogCalls() {
        Production p = createProduction(ProductionStatus.SOLICITADO);
        Long prodId = p.getId();

        // 1. Tarea avanza a generación 2
        taskService.initOrUpdateIntent(prodId, "[{\"equipmentId\":101,\"quantity\":2}]"); // gen 1
        taskService.initOrUpdateIntent(prodId, "[{\"equipmentId\":101,\"quantity\":2}]"); // gen 2

        reset(catalogClient);

        // --- ESCENARIO DE CONFLICTO (desde ProductionService) ---
        // Una petición demorada con generación obsoleta (1L) intenta completar confirmación en ProductionService.
        // Falla localmente por generación obsoleta; ProductionService captura la excepción y evalúa
        // tryBeginCompensation(prodId, 1L, ...). Al detectar conflicto (gen 1 vs gen 2 activa),
        // tryBeginCompensation retorna false, y ProductionService omite la llamada externa a Catalog.
        assertThrows(IllegalStateException.class, () ->
                productionService.completeConfirmation(prodId, "actor-stale", 1L)
        );

        // Verificamos que ProductionService OMITIÓ la llamada externa a Catalog
        verify(catalogClient, never()).compensateInventory(any(), any());
        verify(catalogClient, never()).compensateInventory(any());

        // La tarea en BD debe permanecer intacta en PROCESSING bajo gen 2
        InventoryReservationTask dbTask = taskRepository.findByProductionId(prodId).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_PROCESSING, dbTask.getStatus());
        assertEquals(2L, dbTask.getReservationGeneration());

        // --- ESCENARIO DE CONFLICTO ADICIONAL (desde InventoryReservationRecoveryWorker) ---
        // Simular que un worker ejecuta una tarea con generación obsoleta (1L)
        InventoryReservationTask staleWorkerTask = new InventoryReservationTask(prodId, "[{\"equipmentId\":101,\"quantity\":2}]", InventoryReservationTask.STATUS_COMPENSATING);
        staleWorkerTask.setReservationGeneration(1L);

        recoveryWorker.processTaskRecovery(staleWorkerTask);

        // Catalog sigue con 0 llamadas
        verify(catalogClient, never()).compensateInventory(any(), any());
        verify(catalogClient, never()).compensateInventory(any());

        // --- CONTROL POSITIVO (Compensación válida sí se ejecuta desde ProductionService) ---
        // Creamos una nueva producción donde la compensación es legítima bajo su generación vigente (gen 1)
        Production pPos = createProduction(ProductionStatus.SOLICITADO);
        Long prodIdPos = pPos.getId();
        InventoryReservationTask taskPos = taskService.initOrUpdateIntent(prodIdPos, "[{\"equipmentId\":301,\"quantity\":3}]");
        Long genPos = taskPos.getReservationGeneration();
        assertEquals(1L, genPos);

        // Simulamos que antes de confirmar, la producción fue cancelada concurrentemente
        pPos.setStatus(ProductionStatus.CANCELADO);
        productionRepository.saveAndFlush(pPos);

        // Al invocar completeConfirmation en ProductionService, falla localmente por cancelación,
        // pero la generación coincide (1L == 1L), autorizando la compensación técnica.
        assertThrows(IllegalStateException.class, () ->
                productionService.completeConfirmation(prodIdPos, "actor-valid", genPos)
        );

        // CONTROL POSITIVO: Aquí la compensación SÍ fue autorizada y ejecutada hacia Catalog
        verify(catalogClient, times(1)).compensateInventory(eq(prodIdPos), eq(genPos));

        // La tarea en BD queda confirmada como COMPENSATED
        InventoryReservationTask dbTaskPos = taskRepository.findByProductionId(prodIdPos).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_COMPENSATED, dbTaskPos.getStatus());
        assertEquals(genPos, dbTaskPos.getReservationGeneration());
    }
}
