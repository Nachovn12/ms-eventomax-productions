package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.integration.catalog.dto.CatalogReservationResponseDTO;
import cl.duoc.eventomax.productions.model.InventoryReservationTask;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.InventoryReservationTaskRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryReservationRecoveryWorkerTest {

    @Mock
    private InventoryReservationTaskRepository taskRepository;

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
    void processTaskRecovery_productionSolicitado_activeReservation_compensatesAndMarksCompensated() {
        production.setStatus(ProductionStatus.SOLICITADO);
        when(productionRepository.findById(100L)).thenReturn(Optional.of(production));
        when(catalogClient.getReservationsByProduction(100L)).thenReturn(List.of(
                new CatalogReservationResponseDTO(1L, 1L, "Audio", 2, "RESERVED")
        ));

        recoveryWorker.processTaskRecovery(task);

        verify(catalogClient, times(1)).compensateInventory(100L);
        assertEquals(InventoryReservationTask.STATUS_COMPENSATED, task.getStatus());
        verify(taskRepository).save(task);
    }

    @Test
    void processTaskRecovery_productionSolicitado_noActiveReservation_marksFailed() {
        production.setStatus(ProductionStatus.SOLICITADO);
        when(productionRepository.findById(100L)).thenReturn(Optional.of(production));
        when(catalogClient.getReservationsByProduction(100L)).thenReturn(List.of());

        recoveryWorker.processTaskRecovery(task);

        verify(catalogClient, never()).compensateInventory(any());
        assertEquals(InventoryReservationTask.STATUS_FAILED, task.getStatus());
        verify(taskRepository).save(task);
    }

    @Test
    void processTaskRecovery_productionConfirmado_marksConfirmed() {
        production.setStatus(ProductionStatus.CONFIRMADO);
        when(productionRepository.findById(100L)).thenReturn(Optional.of(production));

        recoveryWorker.processTaskRecovery(task);

        verifyNoInteractions(catalogClient);
        assertEquals(InventoryReservationTask.STATUS_CONFIRMED, task.getStatus());
        verify(taskRepository).save(task);
    }
}
