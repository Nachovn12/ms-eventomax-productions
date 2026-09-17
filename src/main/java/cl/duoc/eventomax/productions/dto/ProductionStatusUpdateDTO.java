package cl.duoc.eventomax.productions.dto;

import cl.duoc.eventomax.productions.model.ProductionStatus;
import jakarta.validation.constraints.NotNull;

public record ProductionStatusUpdateDTO(

        @NotNull(message = "El estado es obligatorio")
        ProductionStatus status

) {}

