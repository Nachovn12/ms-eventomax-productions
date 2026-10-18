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
    ProductionService productionService;

    @Autowired
    ProductionRepository productionRepository;

    @Autowired
    InventoryReservationTaskRepository taskRepository;

    @Autowired
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

        doNothing().when(catalogClient).compensateInventory(p.getId());

        // El usuario cancela la producción
        productionService.updateStatus(p.getId(), new ProductionStatusUpdateDTO(
                ProductionStatus.CANCELADO,
                null
        ), "test-actor");

        // 1. Verificamos que la producción está cancelada
        Production current = productionRepository.findById(p.getId()).orElseThrow();
        assertEquals(ProductionStatus.CANCELADO, current.getStatus());

        // 2. Verificamos compensación en Catalog invocada
        verify(catalogClient, times(1)).compensateInventory(p.getId());

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
        doNothing().when(catalogClient).compensateInventory(p.getId());

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
        verify(catalogClient, times(1)).compensateInventory(p.getId());
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

        recoveryWorker.processTaskRecovery(task);

        verify(catalogClient, times(1)).compensateInventory(p.getId());
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
        doNothing().when(catalogClient).compensateInventory(p.getId());

        boolean claimed = taskService.claimTaskForRecovery(task);
        assertTrue(claimed, "El worker debe poder reclamar la tarea PROCESSING con lease expirado");
        recoveryWorker.processTaskRecovery(task);

        verify(catalogClient, times(1)).compensateInventory(p.getId());
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
        doNothing().when(catalogClient).compensateInventory(p.getId());

        // Simulamos que antes de completeConfirmation, otra transacción canceló la producción
        p.setStatus(ProductionStatus.CANCELADO);
        productionRepository.saveAndFlush(p);

        assertThrows(IllegalStateException.class, () -> {
            productionService.completeConfirmation(p.getId(), "test-actor");
        });

        verify(catalogClient, times(1)).compensateInventory(p.getId());
        InventoryReservationTask task = taskRepository.findByProductionId(p.getId()).orElseThrow();
        assertEquals(InventoryReservationTask.STATUS_COMPENSATED, task.getStatus());
    }

    @Test
    @DisplayName("9. Compensaciones repetidas son seguras e idempotentes")
    void testRepeatedCompensations_areSafeAndIdempotent() {
        Production p = createProduction(ProductionStatus.CANCELADO);
        InventoryReservationTask task = new InventoryReservationTask(p.getId(), "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_COMPENSATING);
        taskRepository.saveAndFlush(task);

        doNothing().when(catalogClient).compensateInventory(p.getId());

        recoveryWorker.processTaskRecovery(task);
        verify(catalogClient, times(1)).compensateInventory(p.getId());

        recoveryWorker.processTaskRecovery(task);
        verify(catalogClient, times(2)).compensateInventory(p.getId());

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
            stateCoordinator.completeConfirmation(p.getId(), "actor-test");
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
}
