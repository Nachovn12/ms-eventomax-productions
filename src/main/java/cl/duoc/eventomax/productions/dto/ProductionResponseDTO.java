package cl.duoc.eventomax.productions.dto;

import java.time.LocalDateTime;

public record ProductionResponseDTO(
        Long id,
        String organizerId,
        String name,
        LocalDateTime scheduledAt,
        String location,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
