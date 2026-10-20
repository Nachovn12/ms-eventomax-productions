package cl.duoc.eventomax.productions.messaging.rabbitmq;

/**
 * Contrato V1 compatible con ms-eventomax-notify para el ticket de cuadrilla.
 */
public record CrewTicketPayload(
        Long productionId,
        String productionName,
        String scheduledAt,
        String location,
        String status
) {}
