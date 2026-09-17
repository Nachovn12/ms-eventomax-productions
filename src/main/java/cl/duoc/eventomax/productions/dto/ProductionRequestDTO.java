package cl.duoc.eventomax.productions.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record ProductionRequestDTO(

        @NotBlank(message = "El organizador es obligatorio")
        @Size(max = 255, message = "El organizador no debe exceder 255 caracteres")
        String organizerId,

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 150, message = "El nombre no debe exceder 150 caracteres")
        String name,

        @NotNull(message = "La fecha programada es obligatoria")
        @FutureOrPresent(message = "La fecha programada debe ser presente o futura")
        LocalDateTime scheduledAt,

        @NotBlank(message = "La ubicacion es obligatoria")
        @Size(max = 255, message = "La ubicacion no debe exceder 255 caracteres")
        String location

) {}

