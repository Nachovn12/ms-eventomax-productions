package cl.duoc.eventomax.productions.event;

import java.time.Instant;
import java.time.LocalDateTime;
import cl.duoc.eventomax.productions.model.ProductionStatus;

public record ProductionStatusChangedEvent(
        Long productionId,
        String organizerId,
        String productionName,
        ProductionStatus previousStatus,
        ProductionStatus newStatus,
        LocalDateTime scheduledAt,
        String location,
        Instant occurredAt
) {}
