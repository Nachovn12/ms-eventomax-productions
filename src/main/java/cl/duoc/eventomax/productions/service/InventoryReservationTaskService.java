package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.model.InventoryReservationTask;
import cl.duoc.eventomax.productions.repository.InventoryReservationTaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

    public boolean areServicesCompatible(String existingJson, String requestedJson) {
        boolean existingEmpty = existingJson == null || existingJson.trim().isEmpty() || existingJson.trim().equals("[]");
        boolean requestedEmpty = requestedJson == null || requestedJson.trim().isEmpty() || requestedJson.trim().equals("[]");
        if (existingEmpty && requestedEmpty) {
            return true;
        }
        if (existingEmpty != requestedEmpty) {
            return false;
        }
        if (existingJson.trim().equals(requestedJson.trim())) {
            return true;
        }
        try {
            Set<Long> existingIds = parseServicesToIds(existingJson);
            Set<Long> requestedIds = parseServicesToIds(requestedJson);
            return existingIds.equals(requestedIds);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean areServicesCompatibleWithIds(String existingJson, List<Long> requestedIds) {
        boolean existingEmpty = existingJson == null || existingJson.trim().isEmpty() || existingJson.trim().equals("[]");
        boolean requestedEmpty = requestedIds == null || requestedIds.isEmpty();
        if (existingEmpty && requestedEmpty) {
            return true;
        }
        if (existingEmpty != requestedEmpty) {
            return false;
        }
        try {
            Set<Long> existing = parseServicesToIds(existingJson);
            Set<Long> requested = new HashSet<>(requestedIds);
            return existing.equals(requested);
        } catch (Exception e) {
            return false;
        }
    }

    private Set<Long> parseServicesToIds(String json) throws Exception {
        if (json == null || json.trim().isEmpty() || json.trim().equals("[]")) {
            return Collections.emptySet();
        }
        TypeReference<List<cl.duoc.eventomax.productions.dto.ContractedServiceDto>> typeRef =
                new TypeReference<>() {};
        List<cl.duoc.eventomax.productions.dto.ContractedServiceDto> list = objectMapper.readValue(json, typeRef);
        return list.stream()
                .map(cl.duoc.eventomax.productions.dto.ContractedServiceDto::serviceId)
                .collect(Collectors.toSet());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InventoryReservationTask initOrUpdateIntent(Long productionId, String itemsJson) {
        return initOrUpdateIntent(productionId, itemsJson, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InventoryReservationTask initOrUpdateIntent(Long productionId, String itemsJson, String servicesJson) {
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
        task.setServicesJson(servicesJson);
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
    public int markConfirmed(Long productionId, Long generation) {
        LocalDateTime now = LocalDateTime.now();
        if (generation != null) {
            return taskRepository.markConfirmedForGeneration(productionId, generation, now);
        } else {
            return taskRepository.markConfirmedLegacy(productionId, now);
        }
    }

    @Transactional
    public void markConfirmed(Long productionId) {
        markConfirmed(productionId, null);
    }

    @Transactional
    public int markCompensating(Long productionId, Long generation, String error) {
        LocalDateTime now = LocalDateTime.now();
        if (generation != null) {
            return taskRepository.markCompensatingForGeneration(productionId, generation, error, now);
        } else {
            return taskRepository.markCompensatingLegacy(productionId, error, now);
        }
    }

    @Transactional
    public void markCompensating(Long productionId, String error) {
        markCompensating(productionId, null, error);
    }

    @Transactional
    public int markCompensated(Long productionId, Long generation) {
        LocalDateTime now = LocalDateTime.now();
        if (generation != null) {
            return taskRepository.markCompensatedForGeneration(productionId, generation, now);
        } else {
            return taskRepository.markCompensatedLegacy(productionId, now);
        }
    }

    @Transactional
    public void markCompensated(Long productionId) {
        markCompensated(productionId, null);
    }

    @Transactional
    public int markFailed(Long productionId, Long generation, String error) {
        LocalDateTime now = LocalDateTime.now();
        if (generation != null) {
            return taskRepository.markFailedForGeneration(productionId, generation, error, now);
        } else {
            return taskRepository.markFailedLegacy(productionId, error, now);
        }
    }

    @Transactional
    public void markFailed(Long productionId, String error) {
        markFailed(productionId, null, error);
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

