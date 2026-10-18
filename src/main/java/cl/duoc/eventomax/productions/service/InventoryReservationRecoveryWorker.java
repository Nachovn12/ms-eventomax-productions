package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.integration.catalog.dto.CatalogReservationResponseDTO;
import cl.duoc.eventomax.productions.model.InventoryReservationTask;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class InventoryReservationRecoveryWorker {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationRecoveryWorker.class);

    private final InventoryReservationTaskService taskService;
    private final ProductionRepository productionRepository;
    private final CatalogClient catalogClient;

    public InventoryReservationRecoveryWorker(
            InventoryReservationTaskService taskService,
            ProductionRepository productionRepository,
            CatalogClient catalogClient) {
        this.taskService = taskService;
        this.productionRepository = productionRepository;
        this.catalogClient = catalogClient;
    }

    @Scheduled(fixedDelayString = "${eventomax.reservation-recovery.delay-ms:5000}", initialDelayString = "${eventomax.reservation-recovery.initial-delay-ms:5000}")
    public void recoverStuckTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(15);
        List<InventoryReservationTask> claimableTasks = taskService.findClaimableTasks(cutoff);

        for (InventoryReservationTask task : claimableTasks) {
            boolean claimed = taskService.claimTaskForRecovery(task);
            if (claimed) {
                processTaskRecovery(task);
            }
        }
    }

    public void processTaskRecovery(InventoryReservationTask task) {
        Production production = productionRepository.findById(task.getProductionId()).orElse(null);
        if (production == null) {
            taskService.markFailed(task.getProductionId(), "Production not found");
            return;
        }

        if (production.getStatus() == ProductionStatus.SOLICITADO) {
            try {
                List<CatalogReservationResponseDTO> reservations = catalogClient.getReservationsByProduction(task.getProductionId());
                boolean hasActiveReservations = reservations != null && reservations.stream()
                        .anyMatch(r -> "RESERVED".equalsIgnoreCase(r.status()));

                if (hasActiveReservations || InventoryReservationTask.STATUS_COMPENSATING.equals(task.getStatus())) {
                    log.warn("Recovering orphan reservation for production {} by technical compensation", task.getProductionId());
                    catalogClient.compensateInventory(task.getProductionId());
                    taskService.markCompensated(task.getProductionId());
                } else {
                    taskService.markFailed(task.getProductionId(), "No active reservations found in catalog for unconfirmed production");
                }
            } catch (Exception e) {
                log.error("Failed to recover task for production {}, will retry", task.getProductionId(), e);
                taskService.markCompensating(task.getProductionId(), e.getMessage());
            }
        } else if (production.getStatus() == ProductionStatus.CONFIRMADO) {
            taskService.markConfirmed(task.getProductionId());
        } else if (production.getStatus() == ProductionStatus.CANCELADO) {
            try {
                log.warn("Ensuring compensation for cancelled production {}", task.getProductionId());
                catalogClient.compensateInventory(task.getProductionId());
                taskService.markCompensated(task.getProductionId());
            } catch (Exception e) {
                log.error("Failed to compensate cancelled production {}, will retry", task.getProductionId(), e);
                taskService.markCompensating(task.getProductionId(), e.getMessage());
            }
        }
    }
}
