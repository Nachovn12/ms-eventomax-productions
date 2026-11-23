package cl.duoc.eventomax.productions.messaging.rabbitmq;

import cl.duoc.eventomax.productions.dto.OrganizerResolutionSource;
import cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent;
import cl.duoc.eventomax.productions.model.OrganizerContact;
import cl.duoc.eventomax.productions.model.OutboxEvent;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.OrganizerContactRepository;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import cl.duoc.eventomax.productions.repository.ProductionContractedServiceRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import cl.duoc.eventomax.productions.service.OrganizerDisplayNameResolver;
import cl.duoc.eventomax.productions.service.OrganizerDisplayNameResolver.ResolvedOrganizer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Suite de pruebas para Corrección 02 · Fase 2A:
 * Enriquecimiento retrocompatible del comando RabbitMQ {@code quote.gen} con identidades reales y cuadrilla.
 */
@ExtendWith(MockitoExtension.class)
class QuoteClosureIdentityEnrichmentTest {

    private static final String CANONICAL_ORGANIZER_ID =
            "f191bdc8-7926-4722-9ec0-cba663701940:11111111-2222-4333-8444-555555555555";
    private static final String CLOSING_ACTOR_ID =
            "f191bdc8-7926-4722-9ec0-cba663701940:7d300514-1e39-4013-85e5-02efa3fd42df";
    private static final String EXTERNAL_ORGANIZER_ID = "ext-productora-sur";

    @Mock
    private RabbitMQCommandPublisher publisher;
    @Mock
    private ProductionContractedServiceRepository serviceRepository;
    @Mock
    private OrganizerContactRepository contactRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private ProductionRepository productionRepository;
    @Mock
    private OrganizerDisplayNameResolver organizerDisplayNameResolver;

    @Captor
    private ArgumentCaptor<QuoteCommandPayload> payloadCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProductionQuoteCommandListener listener;

    @BeforeEach
    void setUp() {
        lenient().when(publisher.publishQuoteCommand(any(QuoteCommandPayload.class))).thenReturn(true);
        listener = new ProductionQuoteCommandListener(
                publisher,
                serviceRepository,
                contactRepository,
                outboxEventRepository,
                productionRepository,
                objectMapper,
                organizerDisplayNameResolver
        );
    }

    private ProductionStatusChangedEvent closingEvent(Long prodId, String organizerId) {
        return new ProductionStatusChangedEvent(
                prodId,
                organizerId,
                "Cumbre Cloud Native 2026",
                ProductionStatus.EN_EJECUCION,
                ProductionStatus.CERRADO,
                LocalDateTime.of(2026, 12, 10, 19, 0),
                "Centro de Eventos Santiago",
                Instant.parse("2026-11-22T21:14:05Z")
        );
    }

    private OutboxEvent outboxEvent(String eventId, String type, String timestamp, String actor, String actorDisplayName,
                                    String prevStatus, String newStatus) {
        String displayNameField = actorDisplayName != null
                ? "\"actorDisplayName\": \"" + actorDisplayName + "\","
                : "";
        String payloadJson = "ProductionCreated".equals(type)
                ? "{\"productionId\": 15, \"productionName\": \"Cumbre Cloud Native 2026\"}"
                : "{\"productionId\": 15, \"previousStatus\": \"" + prevStatus + "\", \"newStatus\": \"" + newStatus + "\"}";
        String json = """
                {
                  "eventId": "%s",
                  "type": "%s",
                  "timestamp": "%s",
                  "traceId": "trace-15",
                  "correlationId": "corr-15",
                  "actor": "%s",
                  %s
                  "payload": %s
                }
                """.formatted(eventId, type, timestamp, actor, displayNameField, payloadJson);
        return new OutboxEvent("Production", "15", type, json);
    }

    private Production productionEntity(Long id, String organizerId, String crewId, String crewName) {
        Production p = new Production(organizerId, "Cumbre Cloud Native 2026",
                LocalDateTime.of(2026, 12, 10, 19, 0), "Centro de Eventos Santiago");
        p.setId(id);
        p.setStatus(ProductionStatus.CERRADO);
        p.setCreatedAt(LocalDateTime.of(2026, 11, 20, 14, 22, 10));
        p.setCrewId(crewId);
        p.setCrewName(crewName);
        return p;
    }

    @Test
    @DisplayName("1. Organizador resuelto por Entra Graph incluye organizerDisplayName y fuente ENTRA_GRAPH")
    void test1_entraGraphOrganizer_resolvedWithSource() {
        when(organizerDisplayNameResolver.resolveOne(CANONICAL_ORGANIZER_ID))
                .thenReturn(new ResolvedOrganizer("Andrea Morales Fuentes", OrganizerResolutionSource.ENTRA_GRAPH));

        listener.handleProductionStatusChanged(closingEvent(15L, CANONICAL_ORGANIZER_ID));

        verify(publisher).publishQuoteCommand(payloadCaptor.capture());
        QuoteCommandPayload payload = payloadCaptor.getValue();
        assertThat(payload.organizerId()).isEqualTo(CANONICAL_ORGANIZER_ID);
        assertThat(payload.organizerDisplayName()).isEqualTo("Andrea Morales Fuentes");
        assertThat(payload.organizerResolutionSource()).isEqualTo("ENTRA_GRAPH");
    }

    @Test
    @DisplayName("2. Organizador externo con contacto autorizado incluye fuente EXTERNAL_CONTACT (no Entra ID)")
    void test2_externalOrganizer_resolvedAsExternalContact() {
        when(organizerDisplayNameResolver.resolveOne(EXTERNAL_ORGANIZER_ID))
                .thenReturn(new ResolvedOrganizer("Productora Sur SpA", OrganizerResolutionSource.EXTERNAL_CONTACT));
        when(contactRepository.findByOrganizerIdAndActiveTrue(EXTERNAL_ORGANIZER_ID))
                .thenReturn(Optional.of(new OrganizerContact(EXTERNAL_ORGANIZER_ID, "contacto@sur.cl", "Productora Sur SpA", null, "actor")));

        listener.handleProductionStatusChanged(closingEvent(15L, EXTERNAL_ORGANIZER_ID));

        verify(publisher).publishQuoteCommand(payloadCaptor.capture());
        QuoteCommandPayload payload = payloadCaptor.getValue();
        assertThat(payload.organizerDisplayName()).isEqualTo("Productora Sur SpA");
        assertThat(payload.organizerResolutionSource()).isEqualTo("EXTERNAL_CONTACT");
        assertThat(payload.organizerResolutionSource()).isNotEqualTo("ENTRA_GRAPH");
    }

    @Test
    @DisplayName("3. Organizador no resuelto establece organizerDisplayName=null y fuente UNRESOLVED")
    void test3_unresolvedOrganizer_returnsNullDisplayNameAndUnresolvedSource() {
        when(organizerDisplayNameResolver.resolveOne(CANONICAL_ORGANIZER_ID))
                .thenReturn(ResolvedOrganizer.UNRESOLVED);

        listener.handleProductionStatusChanged(closingEvent(15L, CANONICAL_ORGANIZER_ID));

        verify(publisher).publishQuoteCommand(payloadCaptor.capture());
        QuoteCommandPayload payload = payloadCaptor.getValue();
        assertThat(payload.organizerDisplayName()).isNull();
        assertThat(payload.organizerResolutionSource()).isEqualTo("UNRESOLVED");
    }

    @Test
    @DisplayName("4. Fallo de Graph/resolver no interrumpe la publicación de quote.gen")
    void test4_graphResolverFailure_degradesSafelyAndStillPublishes() {
        when(organizerDisplayNameResolver.resolveOne(CANONICAL_ORGANIZER_ID))
                .thenThrow(new RuntimeException("Simulated Graph timeout"));

        listener.handleProductionStatusChanged(closingEvent(15L, CANONICAL_ORGANIZER_ID));

        verify(publisher, times(1)).publishQuoteCommand(payloadCaptor.capture());
        QuoteCommandPayload payload = payloadCaptor.getValue();
        assertThat(payload.productionId()).isEqualTo(15L);
        assertThat(payload.organizerDisplayName()).isNull();
        assertThat(payload.organizerResolutionSource()).isEqualTo("UNRESOLVED");
    }

    @Test
    @DisplayName("5. Actor de cierre con actorDisplayName real en Outbox (distinto del organizador)")
    void test5_closingActorWithRealDisplayName_extractedFromOutbox() {
        when(organizerDisplayNameResolver.resolveOne(CANONICAL_ORGANIZER_ID))
                .thenReturn(new ResolvedOrganizer("Andrea Morales Fuentes", OrganizerResolutionSource.ENTRA_GRAPH));
        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("15")).thenReturn(List.of(
                outboxEvent("evt-close-15", "ProductionStatusChanged", "2026-11-22T21:14:05Z",
                        CLOSING_ACTOR_ID, "Carlos Paredes Silva", "EN_EJECUCION", "CERRADO")
        ));

        listener.handleProductionStatusChanged(closingEvent(15L, CANONICAL_ORGANIZER_ID));

        verify(publisher).publishQuoteCommand(payloadCaptor.capture());
        QuoteCommandPayload payload = payloadCaptor.getValue();
        assertThat(payload.organizerDisplayName()).isEqualTo("Andrea Morales Fuentes");
        assertThat(payload.closingActorOid()).isEqualTo(CLOSING_ACTOR_ID);
        assertThat(payload.closingActorDisplayName()).isEqualTo("Carlos Paredes Silva");
        assertThat(payload.kafkaClosingEventId()).isEqualTo("evt-close-15");
    }

    @Test
    @DisplayName("6. Actor de cierre sin actorDisplayName preserva closingActorOid y deja closingActorDisplayName=null")
    void test6_closingActorWithoutDisplayName_neverSubstitutesOrganizer() {
        when(organizerDisplayNameResolver.resolveOne(CANONICAL_ORGANIZER_ID))
                .thenReturn(new ResolvedOrganizer("Andrea Morales Fuentes", OrganizerResolutionSource.ENTRA_GRAPH));
        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("15")).thenReturn(List.of(
                outboxEvent("evt-close-legacy", "ProductionStatusChanged", "2026-11-22T21:14:05Z",
                        CLOSING_ACTOR_ID, null, "EN_EJECUCION", "CERRADO")
        ));

        listener.handleProductionStatusChanged(closingEvent(15L, CANONICAL_ORGANIZER_ID));

        verify(publisher).publishQuoteCommand(payloadCaptor.capture());
        QuoteCommandPayload payload = payloadCaptor.getValue();
        assertThat(payload.closingActorOid()).isEqualTo(CLOSING_ACTOR_ID);
        assertThat(payload.closingActorDisplayName()).isNull();
    }

    @Test
    @DisplayName("7 y 8. Timeline con múltiples actores, eventos históricos sin nombre, orden cronológico y deduplicación")
    void test7and8_timelineWithMultipleActorsAndHistoricalEvents() {
        OutboxEvent e1 = outboxEvent("evt-1", "ProductionCreated", "2026-11-20T14:22:10Z",
                CANONICAL_ORGANIZER_ID, "Andrea Morales Fuentes", null, null);
        OutboxEvent e1Dup = outboxEvent("evt-1", "ProductionCreated", "2026-11-20T14:22:10Z",
                CANONICAL_ORGANIZER_ID, "Andrea Morales Fuentes", null, null);
        OutboxEvent e2Legacy = outboxEvent("evt-2", "ProductionStatusChanged", "2026-11-20T15:05:31Z",
                "legacy-sub-token-01", null, "SOLICITADO", "CONFIRMADO");
        OutboxEvent e3 = outboxEvent("evt-3", "ProductionStatusChanged", "2026-11-22T21:14:05Z",
                CLOSING_ACTOR_ID, "Carlos Paredes Silva", "EN_EJECUCION", "CERRADO");

        // Desordenados a propósito + duplicado para probar ordenamiento y deduplicación
        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("15"))
                .thenReturn(List.of(e3, e1, e2Legacy, e1Dup));

        listener.handleProductionStatusChanged(closingEvent(15L, CANONICAL_ORGANIZER_ID));

        verify(publisher).publishQuoteCommand(payloadCaptor.capture());
        List<QuoteCommandPayload.TimelineItemPayload> timeline = payloadCaptor.getValue().timeline();
        assertThat(timeline).hasSize(3);

        assertThat(timeline.get(0).sequenceIndex()).isEqualTo(1);
        assertThat(timeline.get(0).toStatus()).isEqualTo("SOLICITADO");
        assertThat(timeline.get(0).actor()).isEqualTo(CANONICAL_ORGANIZER_ID);
        assertThat(timeline.get(0).actorDisplayName()).isEqualTo("Andrea Morales Fuentes");

        assertThat(timeline.get(1).sequenceIndex()).isEqualTo(2);
        assertThat(timeline.get(1).fromStatus()).isEqualTo("SOLICITADO");
        assertThat(timeline.get(1).toStatus()).isEqualTo("CONFIRMADO");
        assertThat(timeline.get(1).actor()).isEqualTo("legacy-sub-token-01");
        assertThat(timeline.get(1).actorDisplayName()).isNull();

        assertThat(timeline.get(2).sequenceIndex()).isEqualTo(3);
        assertThat(timeline.get(2).fromStatus()).isEqualTo("EN_EJECUCION");
        assertThat(timeline.get(2).toStatus()).isEqualTo("CERRADO");
        assertThat(timeline.get(2).actorDisplayName()).isEqualTo("Carlos Paredes Silva");
    }

    @Test
    @DisplayName("9 y 10. Cuadrilla presente y cuadrilla ausente en Production")
    void test9and10_crewPresentAndAbsent() {
        // Caso 9: cuadrilla presente
        when(productionRepository.findById(15L))
                .thenReturn(Optional.of(productionEntity(15L, CANONICAL_ORGANIZER_ID, "crew-stgo-sur", "Cuadrilla Técnica Santiago Sur")));
        listener.handleProductionStatusChanged(closingEvent(15L, CANONICAL_ORGANIZER_ID));

        verify(publisher, times(1)).publishQuoteCommand(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().crewId()).isEqualTo("crew-stgo-sur");
        assertThat(payloadCaptor.getValue().crewName()).isEqualTo("Cuadrilla Técnica Santiago Sur");

        // Caso 10: cuadrilla ausente / en blanco
        when(productionRepository.findById(16L))
                .thenReturn(Optional.of(productionEntity(16L, CANONICAL_ORGANIZER_ID, "   ", null)));
        listener.handleProductionStatusChanged(closingEvent(16L, CANONICAL_ORGANIZER_ID));

        verify(publisher, times(2)).publishQuoteCommand(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().crewId()).isNull();
        assertThat(payloadCaptor.getValue().crewName()).isNull();
    }

    @Test
    @DisplayName("11, 12 y 13. Constructores de 7 y 16 campos y compatibilidad JSON con consumidor Notify antiguo")
    void test11_12_13_constructorsAndLegacyNotifyJsonCompatibility() throws Exception {
        // 11. Constructor histórico de 7 campos
        QuoteCommandPayload legacy7 = new QuoteCommandPayload(
                10L, CANONICAL_ORGANIZER_ID, "Prod 10", "CERRADO", "2026-12-10T19:00", "Santiago", "2026-11-22T21:14:05Z");
        assertThat(legacy7.organizerDisplayName()).isNull();
        assertThat(legacy7.crewId()).isNull();

        // 12. Constructor anterior de 16 campos + TimelineItemPayload de 6 campos
        var oldTl = new QuoteCommandPayload.TimelineItemPayload(1, null, "SOLICITADO", "2026-11-20T14:22:10Z", "actor-1", "evt-1");
        assertThat(oldTl.actorDisplayName()).isNull();

        QuoteCommandPayload legacy16 = new QuoteCommandPayload(
                10L, CANONICAL_ORGANIZER_ID, "Prod 10", "CERRADO", "2026-12-10T19:00", "Santiago", "2026-11-22T21:14:05Z",
                "2026-11-20T14:22:10", "an******@duocuc.cl", null, null, List.of(), null, List.of(oldTl), "kfk-1", "actor-1");
        assertThat(legacy16.organizerDisplayName()).isNull();
        assertThat(legacy16.closingActorDisplayName()).isNull();
        assertThat(legacy16.crewName()).isNull();

        // 13. Serialización del payload completo de 21 campos y deserialización por contrato Notify antiguo (16 campos)
        QuoteCommandPayload full21 = new QuoteCommandPayload(
                15L, CANONICAL_ORGANIZER_ID, "Cumbre 2026", "CERRADO", "2026-12-10T19:00", "Santiago", "2026-11-22T21:14:05Z",
                "2026-11-20T14:22:10", "an******@duocuc.cl", "Andrea Morales", null, List.of(), null,
                List.of(new QuoteCommandPayload.TimelineItemPayload(1, "EN_EJECUCION", "CERRADO", "2026-11-22T21:14:05Z", CLOSING_ACTOR_ID, "kfk-15", "Carlos Paredes")),
                "kfk-15", CLOSING_ACTOR_ID,
                "Andrea Morales Fuentes", "ENTRA_GRAPH", "Carlos Paredes", "crew-01", "Cuadrilla Alpha"
        );

        String wireJson = objectMapper.writeValueAsString(full21);

        // Round-trip completo
        QuoteCommandPayload roundTrip = objectMapper.readValue(wireJson, QuoteCommandPayload.class);
        assertThat(roundTrip).isEqualTo(full21);

        // Consumidor Notify antiguo con @JsonIgnoreProperties(ignoreUnknown = true) ignora nuevos campos sin error
        LegacyNotify16FieldsContract oldNotifyParsed = objectMapper.readValue(wireJson, LegacyNotify16FieldsContract.class);
        assertThat(oldNotifyParsed.productionId()).isEqualTo(15L);
        assertThat(oldNotifyParsed.organizerId()).isEqualTo(CANONICAL_ORGANIZER_ID);
        assertThat(oldNotifyParsed.kafkaClosingEventId()).isEqualTo("kfk-15");
        assertThat(oldNotifyParsed.timeline()).hasSize(1);
        assertThat(oldNotifyParsed.timeline().get(0).eventId()).isEqualTo("kfk-15");
    }

    @Test
    @DisplayName("14. Publicación de quote.gen ocurre únicamente en transición a CERRADO")
    void test14_publishesOnlyOnCerrado() {
        for (ProductionStatus nonClosed : List.of(
                ProductionStatus.SOLICITADO,
                ProductionStatus.CONFIRMADO,
                ProductionStatus.EN_MONTAJE,
                ProductionStatus.EN_EJECUCION,
                ProductionStatus.CANCELADO)) {
            listener.handleProductionStatusChanged(new ProductionStatusChangedEvent(
                    15L, CANONICAL_ORGANIZER_ID, "Evento", ProductionStatus.SOLICITADO, nonClosed,
                    LocalDateTime.of(2026, 12, 10, 19, 0), "Santiago", Instant.now()));
        }
        verifyNoInteractions(publisher);
        verifyNoInteractions(organizerDisplayNameResolver);
    }

    @Test
    @DisplayName("15. Re-publicación operativa preserva idempotencia (kafkaClosingEventId) y todos los campos enriquecidos")
    void test15_republishQuoteCommand_preservesIdempotencyAndEnrichedData() {
        when(productionRepository.findById(15L))
                .thenReturn(Optional.of(productionEntity(15L, CANONICAL_ORGANIZER_ID, "crew-stgo-sur", "Cuadrilla Sur")));
        when(organizerDisplayNameResolver.resolveOne(CANONICAL_ORGANIZER_ID))
                .thenReturn(new ResolvedOrganizer("Andrea Morales Fuentes", OrganizerResolutionSource.ENTRA_GRAPH));
        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("15")).thenReturn(List.of(
                outboxEvent("evt-close-idempotent-15", "ProductionStatusChanged", "2026-11-22T21:14:05Z",
                        CLOSING_ACTOR_ID, "Carlos Paredes Silva", "EN_EJECUCION", "CERRADO")
        ));

        boolean ok = listener.republishQuoteCommand(15L, "admin-ops");

        assertThat(ok).isTrue();
        verify(publisher, times(1)).publishQuoteCommand(payloadCaptor.capture());
        QuoteCommandPayload payload = payloadCaptor.getValue();
        assertThat(payload.kafkaClosingEventId()).isEqualTo("evt-close-idempotent-15");
        assertThat(payload.organizerDisplayName()).isEqualTo("Andrea Morales Fuentes");
        assertThat(payload.organizerResolutionSource()).isEqualTo("ENTRA_GRAPH");
        assertThat(payload.closingActorDisplayName()).isEqualTo("Carlos Paredes Silva");
        assertThat(payload.crewId()).isEqualTo("crew-stgo-sur");
        assertThat(payload.crewName()).isEqualTo("Cuadrilla Sur");
        assertThat(payload.timeline()).hasSize(1);
        assertThat(payload.timeline().get(0).actorDisplayName()).isEqualTo("Carlos Paredes Silva");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LegacyNotify16FieldsContract(
            Long productionId,
            String organizerId,
            String productionName,
            String status,
            String scheduledAt,
            String location,
            String closedAt,
            String createdAt,
            String organizerContactMaskedEmail,
            String organizerContactName,
            String organizerContactPhone,
            List<QuoteCommandPayload.ContractedServicePayload> services,
            List<QuoteCommandPayload.EquipmentReservationPayload> equipment,
            List<LegacyNotifyTimelineItem> timeline,
            String kafkaClosingEventId,
            String closingActorOid
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LegacyNotifyTimelineItem(
            Integer sequenceIndex,
            String fromStatus,
            String toStatus,
            String occurredAt,
            String actor,
            String eventId
    ) {}
}
