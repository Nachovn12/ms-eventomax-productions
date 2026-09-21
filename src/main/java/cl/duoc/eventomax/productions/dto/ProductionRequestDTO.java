package cl.duoc.eventomax.productions.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Objeto para la creación de una solicitud de producción de evento")
public record ProductionRequestDTO(

        @Schema(description = "ID o identificador del organizador", example = "org_123456")
        @NotBlank(message = "El organizador es obligatorio")
        @Size(max = 255, message = "El organizador no debe exceder 255 caracteres")
        String organizerId,

        @Schema(description = "Nombre del evento", example = "Concierto de Rock en Vivo")
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 150, message = "El nombre no debe exceder 150 caracteres")
        String name,

        @Schema(description = "Fecha y hora programada para el evento", example = "2026-12-31T20:00:00")
        @NotNull(message = "La fecha programada es obligatoria")
        @FutureOrPresent(message = "La fecha programada debe ser presente o futura")
        LocalDateTime scheduledAt,

        @Schema(description = "Ubicación del evento", example = "Estadio Nacional")
        @NotBlank(message = "La ubicacion es obligatoria")
        @Size(max = 255, message = "La ubicacion no debe exceder 255 caracteres")
        String location

) {}

