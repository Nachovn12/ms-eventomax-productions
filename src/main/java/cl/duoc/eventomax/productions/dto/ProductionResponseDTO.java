package cl.duoc.eventomax.productions.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Objeto que representa una producción de evento en el sistema")
public record ProductionResponseDTO(
        @Schema(description = "ID único de la producción", example = "1")
        Long id,
        
        @Schema(description = "ID del organizador responsable", example = "org_123456")
        String organizerId,
        
        @Schema(description = "Nombre del evento", example = "Concierto de Rock en Vivo")
        String name,
        
        @Schema(description = "Fecha y hora programada", example = "2026-12-31T20:00:00")
        LocalDateTime scheduledAt,
        
        @Schema(description = "Ubicación del evento", example = "Estadio Nacional")
        String location,
        
        @Schema(description = "Estado actual de la producción", example = "SOLICITADO")
        String status,
        
        @Schema(description = "Fecha de creación del registro")
        LocalDateTime createdAt,
        
        @Schema(description = "Fecha de la última actualización del registro")
        LocalDateTime updatedAt
) {}
