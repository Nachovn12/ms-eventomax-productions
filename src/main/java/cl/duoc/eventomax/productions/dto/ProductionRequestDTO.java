package cl.duoc.eventomax.productions.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record ProductionRequestDTO(

        @NotBlank(message = "El organizador es obligatorio")
        String organizerId,

        @NotBlank(message = "El nombre es obligatorio")
        String name,

        @NotNull(message = "La fecha programada es obligatoria")
        @FutureOrPresent(message = "La fecha programada debe ser presente o futura")
        LocalDateTime scheduledAt,

        @NotBlank(message = "La ubicacion es obligatoria")
        String location

) {}
