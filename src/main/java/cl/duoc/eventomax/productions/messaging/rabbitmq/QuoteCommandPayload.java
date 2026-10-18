package cl.duoc.eventomax.productions.messaging.rabbitmq;

/**
 * Payload V1 para el comando {@code GenerateClosingQuote} publicado en {@code cmd.direct}
 * con routing key {@code quote.gen} cuando una producción pasa al estado {@code CERRADO}.
 */
public record QuoteCommandPayload(
        Long productionId,
        String organizerId,
        String productionName,
        String status,
        String scheduledAt,
        String location,
        String closedAt
) {
}

