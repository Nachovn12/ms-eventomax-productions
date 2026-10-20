package cl.duoc.eventomax.productions.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "Servicio contratado para la producción")
public record ContractedServiceDto(
        @Schema(description = "ID del servicio de catálogo", example = "1")
        Long serviceId,

        @Schema(description = "Nombre o denominación del servicio", example = "Catering Premium")
        String serviceName,

        @Schema(description = "Tarifa o precio unitario al momento de contratación", example = "50000.00")
        BigDecimal unitPrice
) {}
