package cl.duoc.eventomax.productions.event;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import cl.duoc.eventomax.productions.dto.ContractedServiceDto;
import cl.duoc.eventomax.productions.model.ProductionStatus;

public record ProductionStatusChangedEvent(
        Long productionId,
        String organizerId,
        String productionName,
        ProductionStatus previousStatus,
        ProductionStatus newStatus,
        LocalDateTime scheduledAt,
        String location,
        Instant occurredAt,
        List<ContractedServiceDto> services,
        String crewId,
        String crewName
) {
    public ProductionStatusChangedEvent(
            Long productionId,
            String organizerId,
            String productionName,
            ProductionStatus previousStatus,
            ProductionStatus newStatus,
            LocalDateTime scheduledAt,
            String location,
            Instant occurredAt) {
        this(productionId, organizerId, productionName, previousStatus, newStatus, scheduledAt, location, occurredAt, null, null, null);
    }

    public ProductionStatusChangedEvent(
            Long productionId,
            String organizerId,
            String productionName,
            ProductionStatus previousStatus,
            ProductionStatus newStatus,
            LocalDateTime scheduledAt,
            String location,
            Instant occurredAt,
            List<ContractedServiceDto> services) {
        this(productionId, organizerId, productionName, previousStatus, newStatus, scheduledAt, location, occurredAt, services, null, null);
    }

    public ProductionStatusChangedEvent(
            Long productionId,
            String organizerId,
            String productionName,
            ProductionStatus previousStatus,
            ProductionStatus newStatus,
            LocalDateTime scheduledAt,
            String location,
            Instant occurredAt,
            String crewId,
            String crewName) {
        this(productionId, organizerId, productionName, previousStatus, newStatus, scheduledAt, location, occurredAt, null, crewId, crewName);
    }
}
