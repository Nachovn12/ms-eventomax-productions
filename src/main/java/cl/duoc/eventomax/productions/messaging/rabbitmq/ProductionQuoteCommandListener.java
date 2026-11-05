package cl.duoc.eventomax.productions.messaging.rabbitmq;

import cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent;
import cl.duoc.eventomax.productions.model.OrganizerContact;
import cl.duoc.eventomax.productions.model.OutboxEvent;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionContractedService;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.OrganizerContactRepository;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import cl.duoc.eventomax.productions.repository.ProductionContractedServiceRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Listener post-commit que enriquece y publica el comando {@code GenerateClosingQuote} hacia
 * {@code cmd.direct} ({@code quote.gen} -> {@code q.cmd.quote}) cuando una producción
 * transiciona al estado {@code CERRADO}.
 * <p>
 * Enriquecimiento documental fidedigno y resiliente:
 * 1. Consulta servicios contratados en {@code production_services} (aislado defensivamente).
 * 2. Consulta contacto autorizado en {@code organizer_contacts} con correo enmascarado.
 * 3. Reconstruye el historial de transiciones cronológico estricto desde {@code outbox_events}
 *    con desempates deterministas y deduplicación por eventId.
 * 4. Extrae dinámicamente el Kafka Closing Event ID y el Actor OID del evento de cierre.
 * 5. Si alguna consulta opcional falla, preserva el valor {@code null} explícito para que el documento
 *    no declare falsamente datos completos inexistentes, y no aborta la publicación hacia RabbitMQ.
 */
@Component
public class ProductionQuoteCommandListener {

    private static final Logger log = LoggerFactory.getLogger(ProductionQuoteCommandListener.class);

    private static final Map<String, Integer> STATUS_SEQUENCE_WEIGHT = Map.of(
            "SOLICITADO", 1,
            "CONFIRMADO", 2,
            "EN_MONTAJE", 3,
            "EN_EJECUCION", 4,
            "CERRADO", 5,
            "CANCELADO", 6
    );

    record InterimTimeline(
            String fromStatus,
            String toStatus,
            String occurredAtStr,
            Instant occurredAtInstant,
            LocalDateTime dbCreatedAt,
            String actor,
            String eventId
    ) {}

    private static final Comparator<InterimTimeline> TIMELINE_COMPARATOR = (a, b) -> {
        // 1. Instante cronológico normalizado (Instant UTC)
        if (a.occurredAtInstant() != null && b.occurredAtInstant() != null) {
            int cmp = a.occurredAtInstant().compareTo(b.occurredAtInstant());
            if (cmp != 0) return cmp;
        } else if (a.occurredAtInstant() != null) {
            return -1; // Timestamps válidos primero
        } else if (b.occurredAtInstant() != null) {
            return 1;
        }

        // 2. Desempate determinista por fecha de persistencia en base de datos (createdAt)
        if (a.dbCreatedAt() != null && b.dbCreatedAt() != null) {
            int cmp = a.dbCreatedAt().compareTo(b.dbCreatedAt());
            if (cmp != 0) return cmp;
        } else if (a.dbCreatedAt() != null) {
            return -1;
        } else if (b.dbCreatedAt() != null) {
            return 1;
        }

        // 3. Desempate por peso de estado de dominio si coinciden timestamps
        int weightA = STATUS_SEQUENCE_WEIGHT.getOrDefault(a.toStatus(), 99);
        int weightB = STATUS_SEQUENCE_WEIGHT.getOrDefault(b.toStatus(), 99);
        if (weightA != weightB) {
            return Integer.compare(weightA, weightB);
        }

        // 4. Desempate determinista final por eventId
        String idA = a.eventId() != null ? a.eventId() : "";
        String idB = b.eventId() != null ? b.eventId() : "";
        return idA.compareTo(idB);
    };

    private final RabbitMQCommandPublisher publisher;
    private final ProductionContractedServiceRepository serviceRepository;
    private final OrganizerContactRepository contactRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ProductionRepository productionRepository;
    private final ObjectMapper objectMapper;

    public ProductionQuoteCommandListener(RabbitMQCommandPublisher publisher) {
        this(publisher, null, null, null, null, new ObjectMapper());
    }

    @Autowired
    public ProductionQuoteCommandListener(
            RabbitMQCommandPublisher publisher,
            ProductionContractedServiceRepository serviceRepository,
            OrganizerContactRepository contactRepository,
            OutboxEventRepository outboxEventRepository,
            ProductionRepository productionRepository,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        this.publisher = publisher;
        this.serviceRepository = serviceRepository;
        this.contactRepository = contactRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.productionRepository = productionRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleProductionStatusChanged(ProductionStatusChangedEvent event) {
        publishQuoteCommandForEvent(event);
    }

    private boolean publishQuoteCommandForEvent(ProductionStatusChangedEvent event) {
        if (event == null || event.newStatus() != ProductionStatus.CERRADO) {
            return false;
        }

        Long productionId = event.productionId();
        String organizerId = event.organizerId();

        try {
            // 1. Fecha de Cierre (closedAt)
            String closedAt = event.occurredAt() != null
                    ? event.occurredAt().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)
                    : Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);

            // 2. Fecha de Creación (createdAt)
            String createdAt = null;
            if (productionRepository != null && productionId != null) {
                try {
                    Optional<Production> prodOpt = productionRepository.findById(productionId);
                    if (prodOpt.isPresent() && prodOpt.get().getCreatedAt() != null) {
                        createdAt = prodOpt.get().getCreatedAt().toString();
                    }
                } catch (Exception ex) {
                    log.warn("Fallo en consulta de producción para createdAt (producción {}): {}", productionId, ex.getMessage());
                }
            }

            // 3. Contacto del Organizador (Minimización y Enmascaramiento de Correo)
            String maskedEmail = null;
            String contactName = null;
            String contactPhone = null;
            if (contactRepository != null && organizerId != null) {
                try {
                    Optional<OrganizerContact> contactOpt = contactRepository.findByOrganizerIdAndActiveTrue(organizerId);
                    if (contactOpt.isPresent()) {
                        OrganizerContact contact = contactOpt.get();
                        contactName = contact.getContactName();
                        contactPhone = contact.getPhone();
                        maskedEmail = maskEmail(contact.getEmail());
                    }
                } catch (Exception ex) {
                    log.warn("Fallo en consulta de contacto para organizador {} (producción {}): {}", organizerId, productionId, ex.getMessage());
                }
            }

            // 4. Servicios Contratados (Preservación estricta de null ante error de consulta)
            List<QuoteCommandPayload.ContractedServicePayload> services = null;
            if (serviceRepository != null && productionId != null) {
                try {
                    List<ProductionContractedService> contractedServices = serviceRepository.findByProductionId(productionId);
                    if (contractedServices != null) {
                        List<QuoteCommandPayload.ContractedServicePayload> serviceList = new ArrayList<>();
                        for (ProductionContractedService s : contractedServices) {
                            if (s == null) continue;
                            serviceList.add(new QuoteCommandPayload.ContractedServicePayload(
                                    s.getServiceId(),
                                    s.getServiceName(),
                                    s.getUnitPrice(),
                                    s.getContractedAt() != null ? s.getContractedAt().toString() : null
                            ));
                        }
                        services = List.copyOf(serviceList);
                    }
                } catch (Exception ex) {
                    log.warn("Fallo en consulta de servicios contratados para producción {}: {}", productionId, ex.getMessage());
                    services = null; // null explícito: información no disponible por fallo de consulta
                }
            }

            // 5. Historial Outbox Cronológico Estricto y Trazabilidad Kafka
            List<QuoteCommandPayload.TimelineItemPayload> timeline = null;
            String kafkaClosingEventId = null;
            String closingActorOid = null;

            if (outboxEventRepository != null && productionId != null) {
                try {
                    List<OutboxEvent> outboxList = outboxEventRepository.findByProductionIdOrderByCreatedAtAsc(String.valueOf(productionId));
                    if (outboxList != null) {
                        timeline = buildChronologicalTimeline(outboxList, productionId);

                        if (timeline != null) {
                            for (int i = timeline.size() - 1; i >= 0; i--) {
                                var item = timeline.get(i);
                                if ("CERRADO".equalsIgnoreCase(item.toStatus())) {
                                    kafkaClosingEventId = item.eventId();
                                    closingActorOid = item.actor();
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.warn("Fallo en consulta de outbox_events para producción {}: {}", productionId, ex.getMessage());
                    timeline = null; // null explícito: historial no disponible por fallo
                }
            }

            // 6. Ensamblado del Payload Enriquecido
            QuoteCommandPayload payload = new QuoteCommandPayload(
                    productionId,
                    organizerId,
                    event.productionName(),
                    event.newStatus().name(),
                    event.scheduledAt() != null ? event.scheduledAt().toString() : null,
                    event.location(),
                    closedAt,
                    createdAt,
                    maskedEmail,
                    contactName,
                    contactPhone,
                    services,
                    null, // Equipamiento queda null: su dominio propietario es Catalog
                    timeline,
                    kafkaClosingEventId,
                    closingActorOid
            );

            // 7. Publicación Resiliente a RabbitMQ
            boolean published = publisher.publishQuoteCommand(payload);
            if (!published) {
                log.error("CRITICAL: Falló la entrega del comando quote.gen a RabbitMQ para producción {}. " +
                        "La producción fue cerrada en BD pero el comando documental no llegó al broker.", productionId);
            } else {
                log.info("Comando quote.gen enriquecido publicado exitosamente para producción {}", productionId);
            }
            return published;

        } catch (Exception e) {
            log.error("CRITICAL: Error inesperado en ProductionQuoteCommandListener para producción {}", productionId, e);
            return false;
        }
    }

    /**
     * Re-publicación operativa o re-drive para una producción ya cerrada.
     * Garantiza idempotencia mediante el identificador kafkaClosingEventId si existe en Outbox.
     */
    public boolean republishQuoteCommand(Long productionId, String actor) {
        if (productionRepository == null || productionId == null) {
            return false;
        }
        Optional<Production> prodOpt = productionRepository.findById(productionId);
        if (prodOpt.isEmpty() || prodOpt.get().getStatus() != ProductionStatus.CERRADO) {
            log.warn("Re-drive quote.gen rechazado: producción {} no existe o no está en CERRADO", productionId);
            return false;
        }
        Production prod = prodOpt.get();
        ProductionStatusChangedEvent event = new ProductionStatusChangedEvent(
                prod.getId(),
                prod.getOrganizerId(),
                prod.getName(),
                ProductionStatus.EN_EJECUCION,
                ProductionStatus.CERRADO,
                prod.getScheduledAt(),
                prod.getLocation(),
                Instant.now(),
                null
        );
        return publishQuoteCommandForEvent(event);
    }

    private List<QuoteCommandPayload.TimelineItemPayload> buildChronologicalTimeline(List<OutboxEvent> outboxList, Long productionId) {
        if (outboxList == null) return null;
        if (outboxList.isEmpty()) return List.of();

        List<InterimTimeline> rawTransitions = new ArrayList<>();
        Set<String> seenEventIds = new HashSet<>();

        for (OutboxEvent oe : outboxList) {
            if (oe == null || oe.getPayload() == null) continue;
            try {
                JsonNode root = objectMapper.readTree(oe.getPayload());
                String eventId = root.path("eventId").asText(null);
                String type = root.path("type").asText(null);
                String occurredAt = root.path("timestamp").asText(null);
                String actor = root.path("actor").asText(null);
                JsonNode payloadNode = root.path("payload");

                String fromStatus = null;
                String toStatus = null;

                if ("ProductionCreated".equalsIgnoreCase(type)) {
                    toStatus = "SOLICITADO";
                } else if ("ProductionStatusChanged".equalsIgnoreCase(type)) {
                    fromStatus = payloadNode.path("previousStatus").asText(null);
                    toStatus = payloadNode.path("newStatus").asText(null);
                }

                if (toStatus != null) {
                    // Deduplicación por eventId si no está vacío
                    if (eventId != null && !eventId.isBlank()) {
                        if (!seenEventIds.add(eventId)) {
                            log.debug("Evento outbox duplicado omitido: eventId={} para producción {}", eventId, productionId);
                            continue;
                        }
                    }

                    Instant occurredAtInstant = parseInstantSafe(occurredAt);
                    LocalDateTime dbCreatedAt = oe.getCreatedAt();

                    rawTransitions.add(new InterimTimeline(
                            fromStatus,
                            toStatus,
                            occurredAt,
                            occurredAtInstant,
                            dbCreatedAt,
                            actor,
                            eventId
                    ));
                }
            } catch (Exception ex) {
                log.warn("Error al deserializar evento outbox {} para producción {}: {}", oe.getId(), productionId, ex.getMessage());
            }
        }

        // Ordenamiento cronológico estricto con desempate determinista
        rawTransitions.sort(TIMELINE_COMPARATOR);

        List<QuoteCommandPayload.TimelineItemPayload> result = new ArrayList<>();
        int seq = 1;
        for (InterimTimeline it : rawTransitions) {
            result.add(new QuoteCommandPayload.TimelineItemPayload(
                    seq++,
                    it.fromStatus(),
                    it.toStatus(),
                    it.occurredAtStr(),
                    it.actor(),
                    it.eventId()
            ));
        }
        return List.copyOf(result);
    }

    public static Instant parseInstantSafe(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(dateStr);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(dateStr).toInstant();
            } catch (DateTimeParseException ex) {
                try {
                    return LocalDateTime.parse(dateStr.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return "********";
        }
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (local.length() <= 2) {
            return local.charAt(0) + "******" + domain;
        }
        return local.substring(0, 2) + "******" + domain;
    }
}
