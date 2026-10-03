package cl.duoc.eventomax.productions.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Ítem de inventario a reservar para la producción")
public record ProductionReservationItemDTO(

        @Schema(description = "Identificador del equipamiento en Catalog", example = "1")
        @NotNull(message = "El equipmentId es obligatorio")
        @Positive(message = "El equipmentId debe ser positivo")
        Long equipmentId,

        @Schema(description = "Cantidad a reservar", example = "2")
        @NotNull(message = "La cantidad es obligatoria")
        @Positive(message = "La cantidad debe ser positiva")
        Integer quantity
) {}
