package cl.duoc.eventomax.productions.messaging.rabbitmq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payload para el comando de generación de acta de cierre / cotización PDF ("GenerateClosingQuote").
 * <p>
 * Preserva retrocompatibilidad estricta con el contrato de 7 campos históricos,
 * admitiendo campos opcionales enriquecidos con datos reales del dominio de Producciones.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuoteCommandPayload(
        // 7 Campos Obligatorios Clásicos (Retrocompatibilidad total)
        Long productionId,
        String organizerId,
        String productionName,
        String status,
        String scheduledAt,
        String location,
        String closedAt,

        // Campos Opcionales Enriquecidos (Documentales)
        String createdAt,
        String organizerContactMaskedEmail,
        String organizerContactName,
        String organizerContactPhone,
        List<ContractedServicePayload> services,
        List<EquipmentReservationPayload> equipment,
        List<TimelineItemPayload> timeline,
        String kafkaClosingEventId,
        String closingActorOid
) {

    /**
     * Constructor de compatibilidad para mensajes y tests históricos de 7 campos.
     */
    public QuoteCommandPayload(
            Long productionId,
            String organizerId,
            String productionName,
            String status,
            String scheduledAt,
            String location,
            String closedAt
    ) {
        this(
                productionId,
                organizerId,
                productionName,
                status,
                scheduledAt,
                location,
                closedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContractedServicePayload(
            Long serviceId,
            String serviceName,
            BigDecimal unitPrice,
            String contractedAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EquipmentReservationPayload(
            Long equipmentId,
            String equipmentName,
            Integer quantity,
            String reservationStatus
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimelineItemPayload(
            Integer sequenceIndex,
            String fromStatus,
            String toStatus,
            String occurredAt,
            String actor,
            String eventId
    ) {}
}
