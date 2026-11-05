package cl.duoc.eventomax.productions.event;

import java.time.Instant;
import java.time.LocalDateTime;

public record ProductionCreatedEvent(
        Long productionId,
        String organizerId,
        String productionName,
        String status,
        LocalDateTime scheduledAt,
        String location,
        Instant occurredAt,
        String crewId,
        String crewName
) {
    public ProductionCreatedEvent(Long productionId, String organizerId, String productionName,
                                  String status, LocalDateTime scheduledAt, String location, Instant occurredAt) {
        this(productionId, organizerId, productionName, status, scheduledAt, location, occurredAt, null, null);
    }
}

