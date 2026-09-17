package cl.duoc.eventomax.productions.dto;

import cl.duoc.eventomax.productions.model.ProductionStatus;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Objeto para solicitar la transición de estado de una producción")
public record ProductionStatusUpdateDTO(

        @Schema(description = "Nuevo estado a asignar (SOLICITADO, CONFIRMADO, EN_MONTAJE, EN_EJECUCION, CERRADO, CANCELADO)", example = "CONFIRMADO")
        @NotNull(message = "El estado es obligatorio")
        ProductionStatus status

) {}

