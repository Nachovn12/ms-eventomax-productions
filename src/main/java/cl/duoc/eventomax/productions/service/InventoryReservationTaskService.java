package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.model.InventoryReservationTask;
import cl.duoc.eventomax.productions.repository.InventoryReservationTaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class InventoryReservationTaskService {

    private final InventoryReservationTaskRepository taskRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InventoryReservationTaskService(InventoryReservationTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public boolean areItemsCompatible(String existingJson, String requestedJson) {
        if (existingJson == null || requestedJson == null) {
            return Objects.equals(existingJson, requestedJson);
        }
        if (existingJson.trim().equals(requestedJson.trim())) {
            return true;
        }
        try {
            Map<Long, Integer> existingMap = parseItemsToMap(existingJson);
            Map<Long, Integer> requestedMap = parseItemsToMap(requestedJson);
            return existingMap.equals(requestedMap);
        } catch (Exception e) {
            return false;
        }
    }

    private Map<Long, Integer> parseItemsToMap(String json) throws Exception {
        TypeReference<List<cl.duoc.eventomax.productions.dto.ProductionReservationItemDTO>> typeRef =
                new TypeReference<>() {};
        List<cl.duoc.eventomax.productions.dto.ProductionReservationItemDTO> list = objectMapper.readValue(json, typeRef);
        Map<Long, Integer> map = new HashMap<>();
        for (cl.duoc.eventomax.productions.dto.ProductionReservationItemDTO item : list) {
            map.merge(item.equipmentId(), item.quantity(), Integer::sum);
        }
        return map;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InventoryReservationTask initOrUpdateIntent(Long productionId, String itemsJson) {
        InventoryReservationTask task = taskRepository.findByProductionId(productionId)
                .orElseGet(() -> new InventoryReservationTask(productionId, itemsJson, InventoryReservationTask.STATUS_PENDING));
        if (task.getId() != null) {
            Long nextGen = task.getReservationGeneration() != null ? task.getReservationGeneration() + 1L : 1L;
            task.setReservationGeneration(nextGen);
        } else {
            if (task.getReservationGeneration() == null) {
                task.setReservationGeneration(1L);
            }
        }
        task.setItemsJson(itemsJson);
        task.setStatus(InventoryReservationTask.STATUS_PROCESSING);
        task.setAttemptCount(task.getAttemptCount() == null ? 1 : task.getAttemptCount() + 1);
        task.setLastError(null);
        return taskRepository.saveAndFlush(task);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimTaskForRecovery(InventoryReservationTask task) {
        LocalDateTime now = LocalDateTime.now();
        int updated = taskRepository.claimTask(task.getId(), task.getStatus(), task.getAttemptCount(), now);
        return updated > 0;
    }

    @Transactional
    public void markConfirmed(Long productionId) {
        taskRepository.findByProductionId(productionId).ifPresent(task -> {
            task.setStatus(InventoryReservationTask.STATUS_CONFIRMED);
            task.setLastError(null);
            taskRepository.saveAndFlush(task);
        });
    }

    @Transactional
    public void markCompensating(Long productionId, String error) {
        InventoryReservationTask task = taskRepository.findByProductionId(productionId)
                .orElseGet(() -> new InventoryReservationTask(productionId, "[]", InventoryReservationTask.STATUS_COMPENSATING));
        task.setStatus(InventoryReservationTask.STATUS_COMPENSATING);
        task.setLastError(error);
        taskRepository.saveAndFlush(task);
    }

    @Transactional
    public void markCompensated(Long productionId) {
        taskRepository.findByProductionId(productionId).ifPresent(task -> {
            task.setStatus(InventoryReservationTask.STATUS_COMPENSATED);
            task.setLastError(null);
            taskRepository.saveAndFlush(task);
        });
    }

    @Transactional
    public void markFailed(Long productionId, String error) {
        taskRepository.findByProductionId(productionId).ifPresent(task -> {
            task.setStatus(InventoryReservationTask.STATUS_FAILED);
            task.setLastError(error);
            taskRepository.saveAndFlush(task);
        });
    }

    @Transactional(readOnly = true)
    public Optional<InventoryReservationTask> getTaskByProductionId(Long productionId) {
        return taskRepository.findByProductionId(productionId);
    }

    @Transactional(readOnly = true)
    public List<InventoryReservationTask> findClaimableTasks(LocalDateTime cutoff) {
        return taskRepository.findClaimableTasks(cutoff);
    }
}

