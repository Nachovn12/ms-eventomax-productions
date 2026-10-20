package cl.duoc.eventomax.productions.messaging.rabbitmq;

public record EmailProductionStatusPayload(
        Long productionId,
        String organizerId,
        String recipientEmail,
        String productionName,
        String status,
        String scheduledAt,
        String location
) {
    public EmailProductionStatusPayload(Long productionId, String organizerId, String productionName, String status, String scheduledAt, String location) {
        this(productionId, organizerId, null, productionName, status, scheduledAt, location);
    }
}
