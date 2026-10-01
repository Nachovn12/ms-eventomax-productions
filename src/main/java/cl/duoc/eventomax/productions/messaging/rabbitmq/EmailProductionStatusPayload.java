package cl.duoc.eventomax.productions.messaging.rabbitmq;

public record EmailProductionStatusPayload(
        Long productionId,
        String organizerId,
        String productionName,
        String status,
        String scheduledAt,
        String location
) {}
