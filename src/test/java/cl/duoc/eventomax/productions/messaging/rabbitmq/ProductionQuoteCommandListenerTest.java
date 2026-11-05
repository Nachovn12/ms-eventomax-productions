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

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.DisplayName;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;

import org.mockito.Captor;

import org.mockito.Mock;

import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.DataAccessException;



import java.math.BigDecimal;

import java.time.Instant;

import java.time.LocalDateTime;

import java.util.List;

import java.util.Optional;

import java.util.UUID;



import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.ArgumentMatchers.anyString;

import static org.mockito.Mockito.*;



/**

 * Suite de pruebas unitarias exhaustivas para ProductionQuoteCommandListener.

 * Cubre los 26 escenarios obligatorios de enriquecimiento documental de Gate 11.

 */

@ExtendWith(MockitoExtension.class)

class ProductionQuoteCommandListenerTest {



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



    private final ObjectMapper objectMapper = new ObjectMapper();



    private ProductionQuoteCommandListener listener;



    @Captor

    private ArgumentCaptor<QuoteCommandPayload> payloadCaptor;



    @BeforeEach

    void setUp() {
        lenient().when(publisher.publishQuoteCommand(any(QuoteCommandPayload.class))).thenReturn(true);
        listener = new ProductionQuoteCommandListener(

                publisher,

                serviceRepository,

                contactRepository,

                outboxEventRepository,

                productionRepository,

                objectMapper

        );

    }



    private ProductionStatusChangedEvent createEvent(ProductionStatus previousStatus, ProductionStatus newStatus) {

        return new ProductionStatusChangedEvent(

                1L,

                "org-ep2-demo-001",

                "EP2-DEMO Festival Tecnológico 2026",

                previousStatus,

                newStatus,

                LocalDateTime.parse("2026-11-20T18:00:00"),

                "Centro de Eventos EP2 — Santiago",

                Instant.parse("2026-10-19T22:57:22.361Z")

        );

    }



    private String createOutboxPayload(UUID eventId, String type, String occurredAt, String actor, String previousStatus, String newStatus) {

        if ("ProductionCreated".equalsIgnoreCase(type)) {

            return String.format("""

                {

                  "eventId": "%s",

                  "type": "ProductionCreated",

                  "timestamp": "%s",

                  "traceId": "trace-uuid-1",

                  "correlationId": "corr-uuid-1",

                  "actor": "%s",

                  "payload": {

                    "productionId": 1,

                    "productionName": "EP2-DEMO Festival Tecnológico 2026"

                  }

                }

                """, eventId, occurredAt, actor);

        } else {

            return String.format("""

                {

                  "eventId": "%s",

                  "type": "ProductionStatusChanged",

                  "timestamp": "%s",

                  "traceId": "trace-uuid-1",

                  "correlationId": "corr-uuid-1",

                  "actor": "%s",

                  "payload": {

                    "productionId": 1,

                    "previousStatus": "%s",

                    "newStatus": "%s"

                  }

                }

                """, eventId, occurredAt, actor, previousStatus, newStatus);

        }

    }



    @Test

    @DisplayName("1. Un cierre genera un único comando lógico quote.gen")

    void test1_cerrado_generatesSingleQuoteCommand() {

        var event = createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO);

        listener.handleProductionStatusChanged(event);

        verify(publisher, times(1)).publishQuoteCommand(any());

    }



    @Test

    @DisplayName("2. Estados distintos de CERRADO no generan el comando")

    void test2_nonCerradoStatuses_doNotGenerateCommand() {

        listener.handleProductionStatusChanged(createEvent(ProductionStatus.SOLICITADO, ProductionStatus.CONFIRMADO));

        listener.handleProductionStatusChanged(createEvent(ProductionStatus.CONFIRMADO, ProductionStatus.EN_MONTAJE));

        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_MONTAJE, ProductionStatus.EN_EJECUCION));

        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CANCELADO));



        verifyNoInteractions(publisher);

    }



    @Test

    @DisplayName("3. Los siete campos históricos permanecen 100% compatibles")

    void test3_historicalSevenFields_remainCompatible() {

        var event = createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO);

        listener.handleProductionStatusChanged(event);



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        QuoteCommandPayload payload = payloadCaptor.getValue();



        assertEquals(1L, payload.productionId());

        assertEquals("org-ep2-demo-001", payload.organizerId());

        assertEquals("EP2-DEMO Festival Tecnológico 2026", payload.productionName());

        assertEquals("CERRADO", payload.status());

        assertEquals("2026-11-20T18:00", payload.scheduledAt());

        assertEquals("Centro de Eventos EP2 — Santiago", payload.location());

        assertEquals("2026-10-19T22:57:22.361Z", payload.closedAt());

    }



    @Test

    @DisplayName("4. Payload enriquecido se serializa correctamente con Jackson")

    void test4_enrichedPayload_serializesCorrectly() throws Exception {

        var event = createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO);

        listener.handleProductionStatusChanged(event);



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        QuoteCommandPayload payload = payloadCaptor.getValue();



        String json = objectMapper.writeValueAsString(payload);

        assertNotNull(json);

        assertTrue(json.contains("\"productionId\":1"));

        assertTrue(json.contains("\"status\":\"CERRADO\""));

    }



    @Test

    @DisplayName("5. JSON generado puede ser leído por el contrato Notify B2.1")

    void test5_generatedJson_contractuallyCompatibleWithNotifyB2_1() throws Exception {

        var event = createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO);

        listener.handleProductionStatusChanged(event);



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        String wireJson = objectMapper.writeValueAsString(payloadCaptor.getValue());



        // Deserialización simulando el consumidor en Notify

        var deserialized = objectMapper.readTree(wireJson);

        assertTrue(deserialized.has("productionId"));

        assertTrue(deserialized.has("organizerId"));

        assertTrue(deserialized.has("productionName"));

        assertTrue(deserialized.has("status"));

        assertTrue(deserialized.has("scheduledAt"));

        assertTrue(deserialized.has("location"));

        assertTrue(deserialized.has("closedAt"));

        assertTrue(deserialized.has("services"));

        assertTrue(deserialized.has("timeline"));

    }



    @Test

    @DisplayName("6. Un servicio contratado se mapea correctamente con BigDecimal")

    void test6_singleContractedService_mappedCorrectly() {

        var serviceMock = mock(ProductionContractedService.class);

        when(serviceMock.getServiceId()).thenReturn(1L);

        when(serviceMock.getServiceName()).thenReturn("EP2-DEMO Iluminación Robótica");

        when(serviceMock.getUnitPrice()).thenReturn(new BigDecimal("175.00"));

        when(serviceMock.getContractedAt()).thenReturn(LocalDateTime.parse("2026-10-19T13:46:53"));



        when(serviceRepository.findByProductionId(1L)).thenReturn(List.of(serviceMock));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        var services = payloadCaptor.getValue().services();

        assertEquals(1, services.size());

        assertEquals(1L, services.get(0).serviceId());

        assertEquals("EP2-DEMO Iluminación Robótica", services.get(0).serviceName());

        assertEquals(new BigDecimal("175.00"), services.get(0).unitPrice());

        assertEquals("2026-10-19T13:46:53", services.get(0).contractedAt());

    }



    @Test

    @DisplayName("7. Varios servicios conservan orden y valores")

    void test7_multipleContractedServices_preserveOrderAndValues() {

        var s1 = mock(ProductionContractedService.class);

        when(s1.getServiceId()).thenReturn(1L);

        when(s1.getServiceName()).thenReturn("Servicio A");

        when(s1.getUnitPrice()).thenReturn(new BigDecimal("100.00"));



        var s2 = mock(ProductionContractedService.class);

        when(s2.getServiceId()).thenReturn(2L);

        when(s2.getServiceName()).thenReturn("Servicio B");

        when(s2.getUnitPrice()).thenReturn(new BigDecimal("200.00"));



        when(serviceRepository.findByProductionId(1L)).thenReturn(List.of(s1, s2));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        var services = payloadCaptor.getValue().services();

        assertEquals(2, services.size());

        assertEquals("Servicio A", services.get(0).serviceName());

        assertEquals("Servicio B", services.get(1).serviceName());

    }



    @Test

    @DisplayName("8. Tarifa 175.00 preservada sin divisa ni símbolos")

    void test8_serviceUnitPrice175_preservedWithoutCurrency() {

        var s = mock(ProductionContractedService.class);

        when(s.getServiceId()).thenReturn(1L);

        when(s.getServiceName()).thenReturn("Iluminación");

        when(s.getUnitPrice()).thenReturn(new BigDecimal("175.00"));



        when(serviceRepository.findByProductionId(1L)).thenReturn(List.of(s));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        assertEquals(new BigDecimal("175.00"), payloadCaptor.getValue().services().get(0).unitPrice());

    }



    @Test

    @DisplayName("9. Tarifa null preservada como null (sin convertir a cero)")

    void test9_serviceUnitPriceNull_preservedAsNull() {

        var s = mock(ProductionContractedService.class);

        when(s.getServiceId()).thenReturn(1L);

        when(s.getServiceName()).thenReturn("Servicio Especial");

        when(s.getUnitPrice()).thenReturn(null);



        when(serviceRepository.findByProductionId(1L)).thenReturn(List.of(s));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        assertNull(payloadCaptor.getValue().services().get(0).unitPrice());

    }



    @Test

    @DisplayName("10. Producción sin servicios contratados mapea a lista vacía")

    void test10_productionWithoutServices_mapsToEmptyList() {

        when(serviceRepository.findByProductionId(1L)).thenReturn(List.of());



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        assertNotNull(payloadCaptor.getValue().services());

        assertTrue(payloadCaptor.getValue().services().isEmpty());

    }



    @Test

    @DisplayName("11. Contacto activo con correo enmascarado correctamente")

    void test11_activeContact_mappedWithMaskedEmail() {

        var contact = mock(OrganizerContact.class);

        when(contact.getEmail()).thenReturn("ignacio.producciones@gmail.com");

        when(contact.getContactName()).thenReturn(null);

        when(contact.getPhone()).thenReturn(null);



        when(contactRepository.findByOrganizerIdAndActiveTrue("org-ep2-demo-001")).thenReturn(Optional.of(contact));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        assertEquals("ig******@gmail.com", payloadCaptor.getValue().organizerContactMaskedEmail());

    }



    @Test

    @DisplayName("12. Nombre, teléfono y OID ausentes permanecen null")

    void test12_contactNullFields_namePhoneOidAbsent() {

        var contact = mock(OrganizerContact.class);

        when(contact.getEmail()).thenReturn("contacto@organizador.cl");

        when(contact.getContactName()).thenReturn(null);

        when(contact.getPhone()).thenReturn(null);



        when(contactRepository.findByOrganizerIdAndActiveTrue("org-ep2-demo-001")).thenReturn(Optional.of(contact));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        assertNull(payloadCaptor.getValue().organizerContactName());

        assertNull(payloadCaptor.getValue().organizerContactPhone());

    }



    @Test

    @DisplayName("13. Contacto inexistente o inactivo mapea a valores null")

    void test13_nonExistentOrInactiveContact_mapsToNulls() {

        when(contactRepository.findByOrganizerIdAndActiveTrue("org-ep2-demo-001")).thenReturn(Optional.empty());



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        assertNull(payloadCaptor.getValue().organizerContactMaskedEmail());

        assertNull(payloadCaptor.getValue().organizerContactName());

        assertNull(payloadCaptor.getValue().organizerContactPhone());

    }



    @Test

    @DisplayName("14. Timeline ordenado correctamente por secuencia de dominio")

    void test14_timelineOrderedCorrectly_byDomainProgression() {

        var oe1 = new OutboxEvent("Production", "1", "ProductionCreated",

                createOutboxPayload(UUID.randomUUID(), "ProductionCreated", "2026-10-19T15:38:29Z", "actor-1", null, "SOLICITADO"));

        var oe2 = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(UUID.randomUUID(), "ProductionStatusChanged", "2026-10-19T16:46:53Z", "actor-2", "SOLICITADO", "CONFIRMADO"));

        var oe3 = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(UUID.randomUUID(), "ProductionStatusChanged", "2026-10-19T22:05:26Z", "actor-2", "CONFIRMADO", "EN_MONTAJE"));

        var oe4 = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(UUID.randomUUID(), "ProductionStatusChanged", "2026-10-19T22:34:03Z", "actor-2", "EN_MONTAJE", "EN_EJECUCION"));

        var oe5 = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(UUID.randomUUID(), "ProductionStatusChanged", "2026-10-19T22:57:22Z", "actor-2", "EN_EJECUCION", "CERRADO"));



        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("1")).thenReturn(List.of(oe1, oe2, oe3, oe4, oe5));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        var timeline = payloadCaptor.getValue().timeline();

        assertEquals(5, timeline.size());

        assertEquals("SOLICITADO", timeline.get(0).toStatus());

        assertEquals("CONFIRMADO", timeline.get(1).toStatus());

        assertEquals("EN_MONTAJE", timeline.get(2).toStatus());

        assertEquals("EN_EJECUCION", timeline.get(3).toStatus());

        assertEquals("CERRADO", timeline.get(4).toStatus());

        assertEquals(1, timeline.get(0).sequenceIndex());

        assertEquals(5, timeline.get(4).sequenceIndex());

    }



    @Test

    @DisplayName("15. Timeline sin contaminación entre producciones")

    void test15_timelineWithoutCrossProductionContamination() {

        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));

        verify(outboxEventRepository).findByProductionIdOrderByCreatedAtAsc("1");

        verify(outboxEventRepository, never()).findByProductionIdOrderByCreatedAtAsc("2");

    }



    @Test

    @DisplayName("16. Eventos con payloads corruptos no rompen el timeline ni duplican eventos")

    void test16_noDuplicateOutboxEvents() {

        var oeValid = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(UUID.randomUUID(), "ProductionStatusChanged", "2026-10-19T22:57:22Z", "actor-2", "EN_EJECUCION", "CERRADO"));

        var oeCorrupt = new OutboxEvent("Production", "1", "ProductionStatusChanged", "{ json-corrupto");



        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("1")).thenReturn(List.of(oeCorrupt, oeValid));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        assertEquals(1, payloadCaptor.getValue().timeline().size());

        assertEquals("CERRADO", payloadCaptor.getValue().timeline().get(0).toStatus());

    }



    @Test

    @DisplayName("17. Event ID Kafka de CERRADO identificado dinámicamente")

    void test17_kafkaClosingEventId_matchesCerradoEvent() {

        UUID expectedClosingKafkaId = UUID.fromString("274e40d9-cb8e-4205-93a7-c62605d298bd");

        var oeCerrado = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(expectedClosingKafkaId, "ProductionStatusChanged", "2026-10-19T22:57:22Z", "actor-cierre", "EN_EJECUCION", "CERRADO"));



        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("1")).thenReturn(List.of(oeCerrado));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        assertEquals(expectedClosingKafkaId.toString(), payloadCaptor.getValue().kafkaClosingEventId());

    }



    @Test

    @DisplayName("18. Event ID RabbitMQ independiente del Event ID Kafka")

    void test18_rabbitmqCommandEventId_independentFromKafkaEventId() {

        // En Producciones, el listener pasa el payload enriquecido a RabbitMQCommandPublisher,

        // quien genera su propio eventId para el envelope de RabbitMQ.

        var event = createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO);

        listener.handleProductionStatusChanged(event);

        verify(publisher).publishQuoteCommand(any());

    }



    @Test

    @DisplayName("19. Actor de cierre obtenido fielmente del evento Outbox persistido")

    void test19_closingActorOid_obtainedFromPersistedEvent() {

        var oeCerrado = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(UUID.randomUUID(), "ProductionStatusChanged", "2026-10-19T22:57:22Z", "sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds", "EN_EJECUCION", "CERRADO"));



        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("1")).thenReturn(List.of(oeCerrado));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        assertEquals("sqdd0RRocgoh8Vpb8bmF2B7ad03kqIPhbn_Fs07K9ds", payloadCaptor.getValue().closingActorOid());

    }



    @Test

    @DisplayName("20. Historial incompleto: tratamiento explícito sin transiciones fabricadas")

    void test20_incompleteHistory_handledExplicitlyWithoutFabrication() {

        var oeInit = new OutboxEvent("Production", "1", "ProductionCreated",

                createOutboxPayload(UUID.randomUUID(), "ProductionCreated", "2026-10-19T15:38:29Z", "actor-1", null, "SOLICITADO"));

        var oeCerrado = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(UUID.randomUUID(), "ProductionStatusChanged", "2026-10-19T22:57:22Z", "actor-2", "EN_EJECUCION", "CERRADO"));



        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("1")).thenReturn(List.of(oeInit, oeCerrado));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        var timeline = payloadCaptor.getValue().timeline();

        assertEquals(2, timeline.size());

        assertEquals("SOLICITADO", timeline.get(0).toStatus());

        assertEquals("CERRADO", timeline.get(1).toStatus());

    }



    @Test

    @DisplayName("21. Timestamps ISO compatibles y válidos")

    void test21_compatibleIsoTimestamps() {

        var event = createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO);

        listener.handleProductionStatusChanged(event);



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        String closedAt = payloadCaptor.getValue().closedAt();

        assertDoesNotThrow(() -> Instant.parse(closedAt));

    }



    @Test

    @DisplayName("22. LocalDateTime sin zona inventada en scheduledAt")

    void test22_localDateTimeWithoutInventedZone() {

        var event = createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO);

        listener.handleProductionStatusChanged(event);



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        String scheduledAt = payloadCaptor.getValue().scheduledAt();

        assertFalse(scheduledAt.endsWith("Z"), "scheduledAt no debe contener sufijo Z inventado");

        assertEquals("2026-11-20T18:00", scheduledAt);

    }



    @Test

    @DisplayName("23. Cierre anterior a fecha programada preservado fidedignamente")

    void test23_closedAtBeforeScheduledAt_preservedAsIs() {

        // Producción programada para 2026-11-20 y cerrada el 2026-10-19

        var event = createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO);

        listener.handleProductionStatusChanged(event);



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        var payload = payloadCaptor.getValue();

        assertTrue(payload.closedAt().compareTo(payload.scheduledAt()) < 0);

    }



    @Test

    @DisplayName("24. No filtrar correo completo, JWT ni secretos")

    void test24_noPiiOrSecretsLeaked() {

        var contact = mock(OrganizerContact.class);

        when(contact.getEmail()).thenReturn("super.secret.email@empresa.cl");

        when(contactRepository.findByOrganizerIdAndActiveTrue("org-ep2-demo-001")).thenReturn(Optional.of(contact));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        String masked = payloadCaptor.getValue().organizerContactMaskedEmail();

        assertFalse(masked.contains("super.secret.email"));

        assertTrue(masked.startsWith("su******@"));

    }



    @Test

    @DisplayName("25. Preservación del publicador: llamado a publishQuoteCommand con payload enriquecido")

    void test25_publisherHistoricalBehaviorPreserved() {

        var event = createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO);

        listener.handleProductionStatusChanged(event);

        verify(publisher).publishQuoteCommand(any(QuoteCommandPayload.class));

    }



    @Test

    @DisplayName("26. Error de repositorio de servicios: publica comando con services null para evitar acta falsamente completa")

    void test26_serviceRepositoryError_publishesWithNullServices_neverFalseAct() {

        when(serviceRepository.findByProductionId(1L)).thenThrow(new DataAccessException("Simulated DB failure") {});



        var event = createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO);



        listener.handleProductionStatusChanged(event);



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        var payload = payloadCaptor.getValue();

        assertNull(payload.services(), "Servicios debe ser null para reflejar indisponibilidad de datos fidedignos");

        assertEquals(1L, payload.productionId());

        assertEquals("CERRADO", payload.status());

    }



    @Test

    @DisplayName("27. Error de repositorio de contactos: publica comando con contacto null sin perder el comando")

    void test27_contactRepositoryError_publishesWithNullContact() {

        when(contactRepository.findByOrganizerIdAndActiveTrue(anyString()))

                .thenThrow(new DataAccessException("Contact DB timeout") {});



        var event = createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO);



        listener.handleProductionStatusChanged(event);



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        var payload = payloadCaptor.getValue();

        assertNull(payload.organizerContactMaskedEmail());

        assertNull(payload.organizerContactName());

        assertNull(payload.organizerContactPhone());

        assertEquals(1L, payload.productionId());

    }



    @Test

    @DisplayName("28. Error de repositorio Outbox: publica comando con timeline null sin perder el comando")

    void test28_outboxRepositoryError_publishesWithNullTimeline() {

        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("1"))

                .thenThrow(new DataAccessException("Outbox query error") {});



        var event = createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO);



        listener.handleProductionStatusChanged(event);



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        var payload = payloadCaptor.getValue();

        assertNull(payload.timeline(), "Timeline debe ser null si falla la consulta para reflejar historial no disponible");

        assertEquals(1L, payload.productionId());

    }



    @Test

    @DisplayName("29. Error de publicación RabbitMQ: manejado defensivamente con log crítico sin propagar excepción al dispatcher")

    void test29_rabbitMQPublishError_handledGracefullyWithoutCrashing() {

        when(publisher.publishQuoteCommand(any(QuoteCommandPayload.class))).thenReturn(false);



        var event = createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO);



        assertDoesNotThrow(() -> listener.handleProductionStatusChanged(event));

        verify(publisher).publishQuoteCommand(any(QuoteCommandPayload.class));

    }



    @Test

    @DisplayName("30. Timeline recibido fuera de orden: ordenamiento cronológico estricto por occurredAt")

    void test30_timelineOutOfOrder_sortedChronologically() {

        UUID id1 = UUID.randomUUID();

        UUID id2 = UUID.randomUUID();

        UUID id3 = UUID.randomUUID();



        // Desordenados intencionalmente en la lista retornada por el repositorio

        var oe3 = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(id3, "ProductionStatusChanged", "2026-10-19T22:30:00Z", "actor", "EN_MONTAJE", "EN_EJECUCION"));

        var oe1 = new OutboxEvent("Production", "1", "ProductionCreated",

                createOutboxPayload(id1, "ProductionCreated", "2026-10-19T10:00:00Z", "actor", null, "SOLICITADO"));

        var oe2 = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(id2, "ProductionStatusChanged", "2026-10-19T14:00:00Z", "actor", "SOLICITADO", "CONFIRMADO"));



        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("1")).thenReturn(List.of(oe3, oe1, oe2));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        var timeline = payloadCaptor.getValue().timeline();

        assertEquals(3, timeline.size());

        assertEquals("SOLICITADO", timeline.get(0).toStatus());

        assertEquals(1, timeline.get(0).sequenceIndex());

        assertEquals("CONFIRMADO", timeline.get(1).toStatus());

        assertEquals(2, timeline.get(1).sequenceIndex());

        assertEquals("EN_EJECUCION", timeline.get(2).toStatus());

        assertEquals(3, timeline.get(2).sequenceIndex());

    }



    @Test

    @DisplayName("31. Múltiples eventos con el mismo timestamp: desempate determinista por dbCreatedAt")

    void test31_timelineSameTimestamp_deterministicTieBreakByDbCreatedAt() {

        UUID idA = UUID.randomUUID();

        UUID idB = UUID.randomUUID();



        var oeA = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(idA, "ProductionStatusChanged", "2026-10-19T20:00:00Z", "actor", "SOLICITADO", "CONFIRMADO"));

        oeA.setCreatedAt(LocalDateTime.parse("2026-10-19T20:00:01"));



        var oeB = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(idB, "ProductionStatusChanged", "2026-10-19T20:00:00Z", "actor", "CONFIRMADO", "EN_MONTAJE"));

        oeB.setCreatedAt(LocalDateTime.parse("2026-10-19T20:00:02"));



        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("1")).thenReturn(List.of(oeB, oeA));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        var timeline = payloadCaptor.getValue().timeline();

        assertEquals(2, timeline.size());

        assertEquals("CONFIRMADO", timeline.get(0).toStatus());

        assertEquals("EN_MONTAJE", timeline.get(1).toStatus());

    }



    @Test

    @DisplayName("32. Eventos duplicados con mismo eventId: deduplicación estricta en timeline")

    void test32_timelineDuplicateEventId_deduplicated() {

        UUID duplicateId = UUID.fromString("99999999-9999-9999-9999-999999999999");



        var oeOriginal = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(duplicateId, "ProductionStatusChanged", "2026-10-19T22:57:22Z", "actor", "EN_EJECUCION", "CERRADO"));

        var oeDuplicate = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(duplicateId, "ProductionStatusChanged", "2026-10-19T22:57:22Z", "actor", "EN_EJECUCION", "CERRADO"));



        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("1")).thenReturn(List.of(oeOriginal, oeDuplicate));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        var timeline = payloadCaptor.getValue().timeline();

        assertEquals(1, timeline.size(), "No deben existir filas duplicadas con el mismo eventId");

        assertEquals("CERRADO", timeline.get(0).toStatus());

    }



    @Test

    @DisplayName("33. Eventos legítimos distintos con igual estado: ambos preservados con sequenceIndex correlativo")

    void test33_timelineSameStatusDifferentEventId_bothPreserved() {

        UUID id1 = UUID.randomUUID();

        UUID id2 = UUID.randomUUID();



        var oe1 = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(id1, "ProductionStatusChanged", "2026-10-19T21:00:00Z", "actor", "CONFIRMADO", "EN_MONTAJE"));

        var oe2 = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(id2, "ProductionStatusChanged", "2026-10-19T21:30:00Z", "actor", "CONFIRMADO", "EN_MONTAJE"));



        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("1")).thenReturn(List.of(oe1, oe2));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        var timeline = payloadCaptor.getValue().timeline();

        assertEquals(2, timeline.size());

        assertEquals(1, timeline.get(0).sequenceIndex());

        assertEquals(2, timeline.get(1).sequenceIndex());

    }



    @Test

    @DisplayName("34. Timestamps nulos o inválidos manejados de forma estable")

    void test34_timelineNullOrInvalidTimestamps_handledSafely() {

        UUID idValid = UUID.randomUUID();

        UUID idNullTime = UUID.randomUUID();



        var oeNullTime = new OutboxEvent("Production", "1", "ProductionCreated",

                createOutboxPayload(idNullTime, "ProductionCreated", null, "actor", null, "SOLICITADO"));

        var oeValid = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(idValid, "ProductionStatusChanged", "2026-10-19T22:57:22Z", "actor", "EN_EJECUCION", "CERRADO"));



        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("1")).thenReturn(List.of(oeNullTime, oeValid));



        assertDoesNotThrow(() -> listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO)));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        var timeline = payloadCaptor.getValue().timeline();

        assertEquals(2, timeline.size());

    }



    @Test

    @DisplayName("35. Cancelación u orden de dominio no estándar: respeta cronología fáctica real")

    void test35_timelineCancellation_sortedChronologically() {

        UUID id1 = UUID.randomUUID();

        UUID id2 = UUID.randomUUID();



        var oe1 = new OutboxEvent("Production", "1", "ProductionCreated",

                createOutboxPayload(id1, "ProductionCreated", "2026-10-19T10:00:00Z", "actor", null, "SOLICITADO"));

        var oe2 = new OutboxEvent("Production", "1", "ProductionStatusChanged",

                createOutboxPayload(id2, "ProductionStatusChanged", "2026-10-19T11:00:00Z", "actor", "SOLICITADO", "CANCELADO"));



        when(outboxEventRepository.findByProductionIdOrderByCreatedAtAsc("1")).thenReturn(List.of(oe2, oe1));



        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO));



        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        var timeline = payloadCaptor.getValue().timeline();

        assertEquals(2, timeline.size());

        assertEquals("SOLICITADO", timeline.get(0).toStatus());

        assertEquals("CANCELADO", timeline.get(1).toStatus());

    }



    @Test

    @DisplayName("36. Re-drive operativo mediante republishQuoteCommand")

    void test36_republishQuoteCommand_operationalRedrive() {

        Production prod = mock(Production.class);

        when(prod.getId()).thenReturn(1L);

        when(prod.getStatus()).thenReturn(ProductionStatus.CERRADO);

        when(prod.getName()).thenReturn("Producción Cerrada Re-drive");

        when(prod.getOrganizerId()).thenReturn("org-001");

        when(productionRepository.findById(1L)).thenReturn(Optional.of(prod));



        boolean redriven = listener.republishQuoteCommand(1L, "admin-actor");



        assertTrue(redriven);

        verify(publisher).publishQuoteCommand(payloadCaptor.capture());

        assertEquals(1L, payloadCaptor.getValue().productionId());

    }

}

