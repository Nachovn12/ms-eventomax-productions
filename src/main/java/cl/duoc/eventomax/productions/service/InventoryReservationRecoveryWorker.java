package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.integration.catalog.dto.CatalogReservationResponseDTO;
import cl.duoc.eventomax.productions.model.InventoryReservationTask;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.InventoryReservationTaskRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class InventoryReservationRecoveryWorker {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationRecoveryWorker.class);

    private final InventoryReservationTaskRepository taskRepository;
    private final ProductionRepository productionRepository;
    private final CatalogClient catalogClient;

    public InventoryReservationRecoveryWorker(
            InventoryReservationTaskRepository taskRepository,
            ProductionRepository productionRepository,
            CatalogClient catalogClient) {
        this.taskRepository = taskRepository;
        this.productionRepository = productionRepository;
        this.catalogClient = catalogClient;
    }

    @Scheduled(fixedDelayString = "${eventomax.reservation-recovery.delay-ms:5000}", initialDelayString = "${eventomax.reservation-recovery.initial-delay-ms:5000}")
    public void recoverStuckTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(15);
        List<InventoryReservationTask> stuckTasks = taskRepository.findStuckTasks(cutoff);

        for (InventoryReservationTask task : stuckTasks) {
            processTaskRecovery(task);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processTaskRecovery(InventoryReservationTask task) {
        Production production = productionRepository.findById(task.getProductionId()).orElse(null);
        if (production == null) {
            task.setStatus(InventoryReservationTask.STATUS_FAILED);
            taskRepository.save(task);
            return;
        }

        if (production.getStatus() == ProductionStatus.SOLICITADO) {
            try {
                List<CatalogReservationResponseDTO> reservations = catalogClient.getReservationsByProduction(task.getProductionId());
                boolean hasActiveReservations = reservations.stream().anyMatch(r -> "RESERVED".equals(r.status()));

                if (hasActiveReservations || InventoryReservationTask.STATUS_COMPENSATING.equals(task.getStatus())) {
                    log.warn("Recovering orphan reservation for production {} by technical compensation", task.getProductionId());
                    catalogClient.compensateInventory(task.getProductionId());
                    task.setStatus(InventoryReservationTask.STATUS_COMPENSATED);
                    task.setLastError(null);
                    taskRepository.save(task);
                } else {
                    task.setStatus(InventoryReservationTask.STATUS_FAILED);
                    taskRepository.save(task);
                }
            } catch (Exception e) {
                log.error("Failed to recover task for production {}, will retry", task.getProductionId(), e);
                task.setStatus(InventoryReservationTask.STATUS_COMPENSATING);
                task.setAttemptCount(task.getAttemptCount() + 1);
                task.setLastError(e.getMessage());
                taskRepository.save(task);
            }
        } else if (production.getStatus() == ProductionStatus.CONFIRMADO) {
            task.setStatus(InventoryReservationTask.STATUS_CONFIRMED);
            taskRepository.save(task);
        }
    }
}
