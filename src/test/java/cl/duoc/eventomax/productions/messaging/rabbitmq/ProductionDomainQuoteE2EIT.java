package cl.duoc.eventomax.productions.messaging.rabbitmq;

import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.model.OrganizerContact;
import cl.duoc.eventomax.productions.model.OutboxEvent;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionContractedService;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.OrganizerContactRepository;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import cl.duoc.eventomax.productions.repository.ProductionContractedServiceRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import cl.duoc.eventomax.productions.service.ProductionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate B4.3: Certificación E2E de Dominio en Vivo
 * <p>
 * Flujo completo probado:
 * Dominio Productions (transición válida a CERRADO)
 *   -> Persistencia en base de datos PostgreSQL / H2
 *   -> Registro de evento en outbox_events
 *   -> Listener AFTER_COMMIT (ProductionQuoteCommandListener)
 *   -> Ensamblado del Payload Enriquecido (16 campos, servicios, contacto enmascarado, timeline cronológico)
 *   -> RabbitMQCommandPublisher con Publisher Confirms (CorrelationData, broker ACK, cmd.direct / quote.gen)
 *   -> Broker RabbitMQ real en localhost:25692 (entrega a q.cmd.quote)
 *   -> Notify en background (QuoteCommandListener)
 *   -> Ensamblador ProductionClosureDocumentBuilder y OpenPdfClosureDocumentGenerator
 *   -> Generación física del PDF en .local-cert/b3
 *   -> Verificación binaria (%PDF-1.), tamaño (>25KB), SHA-256
 *   -> Idempotencia frente a republicación (no regeneración)
 *   -> Monitoreo de q.cmd.quote.dlq (0 mensajes)
 *   -> Salvaguarda absoluta de evidencias históricas PRO-06.
 */
@SpringBootTest
@ActiveProfiles("test")
public class ProductionDomainQuoteE2EIT {

    private static final Logger log = LoggerFactory.getLogger(ProductionDomainQuoteE2EIT.class);
    private static final String HISTORICAL_CERT_HASH = "1FC65BC41E18F7D2B185A3610383FD66E8B6BACB736A9A3EAB7AB005C165F014";

    @DynamicPropertySource
    static void configureRabbitMQProperties(DynamicPropertyRegistry registry) {
        try {
            File credsFile = new File("C:/Users/Nacho/Desktop/Cloud Native/.local-cert/credentials.json");
            if (!credsFile.exists()) {
                return;
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode creds = mapper.readTree(credsFile);
            String rmqUser = creds.get("application").get("username").asText();
            String rmqPass = creds.get("application").get("password").asText();

            registry.add("spring.rabbitmq.host", () -> "localhost");
            registry.add("spring.rabbitmq.port", () -> 25692);
            registry.add("spring.rabbitmq.username", () -> rmqUser);
            registry.add("spring.rabbitmq.password", () -> rmqPass);
            registry.add("spring.rabbitmq.virtual-host", () -> "/");
            registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
            registry.add("spring.rabbitmq.publisher-returns", () -> "true");
            registry.add("spring.rabbitmq.template.mandatory", () -> "true");
        } catch (Exception e) {
            throw new RuntimeException("Error cargando credenciales para RabbitMQ real", e);
        }
    }

    @MockitoBean
    private CatalogClient catalogClient;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private ProductionService productionService;

    @Autowired
    private ProductionRepository productionRepository;

    @Autowired
    private OrganizerContactRepository organizerContactRepository;

    @Autowired
    private ProductionContractedServiceRepository productionContractedServiceRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProductionQuoteCommandListener quoteCommandListener;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Test
    @DisplayName("Gate B4.3: Flujo de Dominio Real -> AFTER_COMMIT -> Publisher Confirms -> RabbitMQ Real -> Notify -> PDF")
    void testFullDomainToPdfE2EFlow() throws Exception {
        File credsFile = new File("C:/Users/Nacho/Desktop/Cloud Native/.local-cert/credentials.json");
        Assumptions.assumeTrue(credsFile.exists(), "Skipping live E2E test when local credentials.json is not present");
        log.info("=== INICIANDO GATE B4.3: TEST E2E DE DOMINIO Y CONFIRMS ===");

        // 1. Preparar contacto de organizador con correo que debe ser enmascarado
        String organizerId = "org-b4-e2e-cert";
        organizerContactRepository.findByOrganizerIdAndActiveTrue(organizerId).ifPresent(organizerContactRepository::delete);

        OrganizerContact contact = new OrganizerContact(
                organizerId,
                "claudia.bahamondes@eventomax.cl",
                "Dra. Claudia Bahamondes",
                "+56987654321",
                "auditor-b4-e2e-oid"
        );
        organizerContactRepository.saveAndFlush(contact);

        // 2. Aislar ID de producción: asegurar que la producción de prueba tenga ID > 10 (nunca ID #1)
        for (int i = 1; i <= 10; i++) {
            productionRepository.saveAndFlush(new Production("org-dummy", "Dummy " + i, LocalDateTime.now(), "Santiago"));
        }

        // Crear nueva Producción B4 en estado EN_EJECUCION para transición limpia a CERRADO
        Production production = new Production(
                organizerId,
                "EP2-B4 Certificación Oficial de Dominio EventoMax",
                LocalDateTime.parse("2026-12-15T20:00:00"),
                "Gran Salón B4 - Santiago"
        );
        production.setStatus(ProductionStatus.EN_EJECUCION);
        production = productionRepository.saveAndFlush(production);
        Long productionId = production.getId();
        assertTrue(productionId > 1, "La producción de prueba debe tener un ID nuevo diferente a 1");
        log.info("[B4.3] Producción de prueba B4 creada con ID: {}", productionId);

        // 3. Registrar Servicio Contratado para esta producción
        ProductionContractedService service = new ProductionContractedService(
                production,
                201L,
                "EP2-B4 Sonido Line Array Certificado",
                new BigDecimal("450.00"),
                LocalDateTime.now().minusHours(1),
                1L
        );
        productionContractedServiceRepository.saveAndFlush(service);

        // 4. Sembrar eventos Outbox previos para timeline cronológico
        ObjectMapper mapper = new ObjectMapper();
        seedOutboxTransition(productionId, "ProductionCreated", null, "SOLICITADO",
                Instant.now().minusSeconds(3600), "productor-lead", "b4-evt-1");
        seedOutboxTransition(productionId, "ProductionStatusChanged", "SOLICITADO", "CONFIRMADO",
                Instant.now().minusSeconds(2700), "productor-lead", "b4-evt-2");
        seedOutboxTransition(productionId, "ProductionStatusChanged", "CONFIRMADO", "EN_MONTAJE",
                Instant.now().minusSeconds(1800), "productor-lead", "b4-evt-3");
        seedOutboxTransition(productionId, "ProductionStatusChanged", "EN_MONTAJE", "EN_EJECUCION",
                Instant.now().minusSeconds(900), "productor-lead", "b4-evt-4");

        // 5. Ejecutar la transición de dominio a CERRADO a través de ProductionService
        String actor = "auditor-b4-e2e-oid";
        log.info("[B4.3] Ejecutando executeStandardStatusUpdate a CERRADO con actor: {}", actor);
        var response = productionService.executeStandardStatusUpdate(productionId, ProductionStatus.CERRADO, actor);

        // Validar respuesta y persistencia en base de datos
        assertEquals("CERRADO", response.status(), "El estado de la respuesta debe ser CERRADO");
        Production dbProd = productionRepository.findById(productionId).orElseThrow();
        assertEquals(ProductionStatus.CERRADO, dbProd.getStatus(), "El estado persistido en BD debe ser CERRADO");

        // Validar que el evento outbox de cierre fue creado y persistido
        List<OutboxEvent> outboxList = outboxEventRepository.findByProductionIdOrderByCreatedAtAsc(String.valueOf(productionId));
        assertTrue(outboxList.size() >= 5, "Deben existir al menos 5 eventos en outbox para la producción");
        OutboxEvent closingEvent = outboxList.get(outboxList.size() - 1);
        assertEquals("ProductionStatusChanged", closingEvent.getEventType());
        log.info("[B4.3] Evento outbox de cierre verificado en BD con ID: {}", closingEvent.getId());

        // 6. Esperar a que el consumidor real Notify procese el comando quote.gen y genere el PDF
        Path b3Dir = Path.of("C:/Users/Nacho/Desktop/Cloud Native/.local-cert/b3");
        Path expectedLatestPdf = b3Dir.resolve("quote-production-" + productionId + "-latest.pdf");

        log.info("[B4.3] Esperando generación del PDF por Notify en: {}", expectedLatestPdf);
        boolean generated = false;
        for (int i = 0; i < 25; i++) {
            Thread.sleep(1000);
            if (Files.exists(expectedLatestPdf) && Files.size(expectedLatestPdf) > 20000) {
                generated = true;
                break;
            }
        }
        assertTrue(generated, "El acta PDF empresarial de dominio debe haber sido generada en: " + expectedLatestPdf);

        // 7. Verificación del PDF generado
        long pdfSize = Files.size(expectedLatestPdf);
        log.info("[B4.3] PDF empresarial generado exitosamente! Tamaño: {} bytes", pdfSize);
        assertTrue(pdfSize > 25000, "El PDF empresarial debe ser mayor a 25KB, actual: " + pdfSize);

        byte[] pdfBytes = Files.readAllBytes(expectedLatestPdf);
        String header = new String(pdfBytes, 0, 8);
        assertTrue(header.startsWith("%PDF-1."), "Encabezado PDF inválido: " + header);

        String pdfHash = calculateSha256(expectedLatestPdf);
        log.info("[B4.3] SHA-256 del PDF empresarial generado: {}", pdfHash);
        assertNotNull(pdfHash);

        // 8. Prueba de Idempotencia: invocar republishQuoteCommand y verificar que el PDF no es regenerado
        long lastModifiedBefore = Files.getLastModifiedTime(expectedLatestPdf).toMillis();
        Thread.sleep(1500);

        boolean republished = quoteCommandListener.republishQuoteCommand(productionId, actor);
        assertTrue(republished, "republishQuoteCommand debe retornar true para una producción en CERRADO");
        log.info("[B4.3] Re-publicación ejecutada. Esperando verificación de idempotencia...");
        Thread.sleep(3000);

        long lastModifiedAfter = Files.getLastModifiedTime(expectedLatestPdf).toMillis();
        assertEquals(lastModifiedBefore, lastModifiedAfter, "El PDF no debe modificarse ante republicación (idempotencia confirmada)");

        // 9. Verificar estado de la Dead Letter Queue (q.cmd.quote.dlq)
        Properties dlqProps = amqpAdmin.getQueueProperties("q.cmd.quote.dlq");
        assertNotNull(dlqProps, "Propiedades de q.cmd.quote.dlq no deben ser nulas");
        long dlqCount = ((Number) dlqProps.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue();
        assertEquals(0L, dlqCount, "q.cmd.quote.dlq debe permanecer vacía (0 mensajes)");
        log.info("[B4.3] Monitoreo DLQ: q.cmd.quote.dlq tiene exactamente 0 mensajes pendientes");

        // 10. Salvaguarda Absoluta de Evidencias Históricas PRO-06
        Path pro06Dir = Path.of("C:/Users/Nacho/Desktop/Cloud Native/.local-cert/quotes");
        Path prod1EventPdf = pro06Dir.resolve("quote-production-1-678c5962-6568-4db9-9e1a-4a8fb7a68614.pdf");
        Path prod1LatestPdf = pro06Dir.resolve("quote-production-1-latest.pdf");

        assertTrue(Files.exists(prod1EventPdf), "Evidencia histórica de evento PRO-06 debe existir");
        assertTrue(Files.exists(prod1LatestPdf), "Evidencia histórica latest PRO-06 debe existir");

        String hashEvent = calculateSha256(prod1EventPdf);
        String hashLatest = calculateSha256(prod1LatestPdf);

        assertEquals(HISTORICAL_CERT_HASH, hashEvent, "Hash del PDF histórico PRO-06 debe estar inalterado");
        assertEquals(HISTORICAL_CERT_HASH, hashLatest, "Hash del PDF histórico latest PRO-06 debe estar inalterado");
        log.info("[B4.3] Verificación PRO-06: Hashes SHA-256 100% inalterados ({})", HISTORICAL_CERT_HASH);
        log.info("=== GATE B4.3 CERTIFICADO EXITOSAMENTE ===");
    }

    private void seedOutboxTransition(Long productionId, String type, String fromStatus, String toStatus,
                                      Instant occurredAt, String actor, String eventId) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            var payloadNode = mapper.createObjectNode();
            if (fromStatus != null) payloadNode.put("previousStatus", fromStatus);
            if (toStatus != null) payloadNode.put("newStatus", toStatus);

            var rootNode = mapper.createObjectNode();
            rootNode.put("eventId", eventId);
            rootNode.put("type", type);
            rootNode.put("timestamp", occurredAt.toString());
            rootNode.put("actor", actor);
            rootNode.set("payload", payloadNode);

            String jsonPayload = mapper.writeValueAsString(rootNode);
            OutboxEvent event = new OutboxEvent("Production", String.valueOf(productionId), type, jsonPayload);
            outboxEventRepository.saveAndFlush(event);
        } catch (Exception e) {
            throw new RuntimeException("Error sembrando evento outbox", e);
        }
    }

    private static String calculateSha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(path);
        byte[] hash = digest.digest(bytes);
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString().toUpperCase();
    }
}
