package cl.duoc.eventomax.productions.dto;

import java.time.LocalDateTime;

public record ProductionResponseDTO(
        Long id,
        String name,
        LocalDateTime scheduledAt,
        String location,
        String status
) {}
