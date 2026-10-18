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
    private final ProductionStateCoordinator stateCoordinator;

    public InventoryReservationRecoveryWorker(
            InventoryReservationTaskService taskService,
            ProductionRepository productionRepository,
            CatalogClient catalogClient) {
        this(taskService, productionRepository, catalogClient, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InventoryReservationRecoveryWorker(
            InventoryReservationTaskService taskService,
            ProductionRepository productionRepository,
            CatalogClient catalogClient,
            ProductionStateCoordinator stateCoordinator) {
        this.taskService = taskService;
        this.productionRepository = productionRepository;
        this.catalogClient = catalogClient;
        this.stateCoordinator = stateCoordinator;
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

        Long generation = (task.getAttemptCount() != null && task.getAttemptCount() > 0) ? task.getAttemptCount().longValue() : null;

        if (production.getStatus() == ProductionStatus.SOLICITADO) {
            try {
                List<CatalogReservationResponseDTO> reservations = catalogClient.getReservationsByProduction(task.getProductionId());
                boolean hasActiveReservations = reservations != null && reservations.stream()
                        .anyMatch(r -> "RESERVED".equalsIgnoreCase(r.status()));

                if (hasActiveReservations || InventoryReservationTask.STATUS_COMPENSATING.equals(task.getStatus())) {
                    boolean canCompensate = stateCoordinator == null || stateCoordinator.tryBeginCompensation(
                            task.getProductionId(), generation, "Recovery worker compensation");
                    if (canCompensate) {
                        log.warn("Recovering orphan reservation for production {} by technical compensation", task.getProductionId());
                        if (generation != null) {
                            catalogClient.compensateInventory(task.getProductionId(), generation);
                        } else {
                            catalogClient.compensateInventory(task.getProductionId());
                        }
                        if (stateCoordinator != null) {
                            stateCoordinator.completeCompensation(task.getProductionId(), generation);
                        } else {
                            taskService.markCompensated(task.getProductionId());
                        }
                    } else {
                        log.info("Recovery worker compensation skipped for production {} as state no longer permits compensation", task.getProductionId());
                    }
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
                boolean canCompensate = stateCoordinator == null || stateCoordinator.tryBeginCompensation(
                        task.getProductionId(), generation, "Recovery worker cancelled production");
                if (canCompensate) {
                    log.warn("Ensuring compensation for cancelled production {}", task.getProductionId());
                    if (generation != null) {
                        catalogClient.compensateInventory(task.getProductionId(), generation);
                    } else {
                        catalogClient.compensateInventory(task.getProductionId());
                    }
                    if (stateCoordinator != null) {
                        stateCoordinator.completeCompensation(task.getProductionId(), generation);
                    } else {
                        taskService.markCompensated(task.getProductionId());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to compensate cancelled production {}, will retry", task.getProductionId(), e);
                taskService.markCompensating(task.getProductionId(), e.getMessage());
            }
        }
    }
}
