package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.integration.catalog.dto.CatalogReservationResponseDTO;
import cl.duoc.eventomax.productions.model.InventoryReservationTask;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryReservationRecoveryWorkerTest {

    @Mock
    private InventoryReservationTaskService taskService;

    @Mock
    private ProductionRepository productionRepository;

    @Mock
    private CatalogClient catalogClient;

    @InjectMocks
    private InventoryReservationRecoveryWorker recoveryWorker;

    private Production production;
    private InventoryReservationTask task;

    @BeforeEach
    void setUp() {
        production = new Production();
        production.setId(100L);

        task = new InventoryReservationTask(100L, "[{\"equipmentId\":1,\"quantity\":2}]", InventoryReservationTask.STATUS_PENDING);
    }

    @Test
    void recoverStuckTasks_claimsAndProcessesClaimableTasks() {
        when(taskService.findClaimableTasks(any(LocalDateTime.class))).thenReturn(List.of(task));
        when(taskService.claimTaskForRecovery(task)).thenReturn(true);
        when(productionRepository.findById(100L)).thenReturn(Optional.of(production));
        production.setStatus(ProductionStatus.CONFIRMADO);

        recoveryWorker.recoverStuckTasks();

        verify(taskService).claimTaskForRecovery(task);
        verify(taskService).markConfirmed(100L);
    }

    @Test
    void recoverStuckTasks_skipsUnclaimedTasks() {
        when(taskService.findClaimableTasks(any(LocalDateTime.class))).thenReturn(List.of(task));
        when(taskService.claimTaskForRecovery(task)).thenReturn(false);

        recoveryWorker.recoverStuckTasks();

        verify(taskService).claimTaskForRecovery(task);
        verifyNoInteractions(productionRepository);
    }

    @Test
    void processTaskRecovery_productionSolicitado_activeReservation_compensatesAndMarksCompensated() {
        production.setStatus(ProductionStatus.SOLICITADO);
        when(productionRepository.findById(100L)).thenReturn(Optional.of(production));
        when(catalogClient.getReservationsByProduction(100L)).thenReturn(List.of(
                new CatalogReservationResponseDTO(1L, 1L, "Audio", 2, "RESERVED")
        ));

        recoveryWorker.processTaskRecovery(task);

        verify(catalogClient, times(1)).compensateInventory(eq(100L), any());
        verify(taskService).markCompensated(100L);
    }

    @Test
    void processTaskRecovery_productionSolicitado_noActiveReservation_marksFailed() {
        production.setStatus(ProductionStatus.SOLICITADO);
        when(productionRepository.findById(100L)).thenReturn(Optional.of(production));
        when(catalogClient.getReservationsByProduction(100L)).thenReturn(List.of());

        recoveryWorker.processTaskRecovery(task);

        verify(catalogClient, never()).compensateInventory(any());
        verify(taskService).markFailed(eq(100L), anyString());
    }

    @Test
    void processTaskRecovery_productionConfirmado_marksConfirmed() {
        production.setStatus(ProductionStatus.CONFIRMADO);
        when(productionRepository.findById(100L)).thenReturn(Optional.of(production));

        recoveryWorker.processTaskRecovery(task);

        verifyNoInteractions(catalogClient);
        verify(taskService).markConfirmed(100L);
    }

    @Test
    void processTaskRecovery_productionCancelado_compensatesAndMarksCompensated() {
        production.setStatus(ProductionStatus.CANCELADO);
        when(productionRepository.findById(100L)).thenReturn(Optional.of(production));

        recoveryWorker.processTaskRecovery(task);

        verify(catalogClient, times(1)).compensateInventory(eq(100L), any());
        verify(taskService).markCompensated(100L);
    }
}
