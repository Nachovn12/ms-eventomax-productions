package cl.duoc.eventomax.productions.messaging.common;

public record MessageEnvelope<T>(
        String type,
        String eventId,
        String timestamp,
        String traceId,
        String correlationId,
        T payload
) {}
