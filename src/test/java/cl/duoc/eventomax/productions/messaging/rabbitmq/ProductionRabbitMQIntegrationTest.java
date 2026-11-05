package cl.duoc.eventomax.productions.messaging.rabbitmq;

import cl.duoc.eventomax.productions.dto.ProductionRequestDTO;
import cl.duoc.eventomax.productions.dto.ProductionReservationItemDTO;
import cl.duoc.eventomax.productions.dto.ProductionResponseDTO;
import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.integration.catalog.exception.InventoryReservationException;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.InventoryReservationTaskRepository;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import cl.duoc.eventomax.productions.service.ProductionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;

/**
 * Prueba de integración real del flujo de publicación AMQP en ms-eventomax-productions
 * utilizando H2 en memoria (perfil test) y un broker RabbitMQ efímero mediante Testcontainers.
 * <p>
 * Verifica que {@link ProductionService}, los listeners {@link ProductionEmailCommandListener}
 * y {@link ProductionCrewCommandListener} (fase {@code AFTER_COMMIT}) y {@link RabbitMQCommandPublisher}
 * publican los comandos esperados en {@code cmd.direct} según la máquina de estados y que ningún
 * mensaje se emite si la transacción hace rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductionRabbitMQIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ProductionRabbitMQIntegrationTest.class);

    private static final int RABBITMQ_AMQP_PORT = 5672;
    private static final String PERSISTENT_DEMO_CONTAINER_NAME = "eventomax-test-rabbitmq";
    private static final String PERSISTENT_EP2_CONTAINER_NAME = "eventomax-rabbitmq-ep2";
    private static final String PERSISTENT_DEMO_CONTAINER_ID_PREFIX = "1292e1150a05";
    private static final String PERSISTENT_EP2_CONTAINER_ID_PREFIX = "4fd477bc189b";

    private static final String CMD_DIRECT_EXCHANGE = "cmd.direct";
    private static final String Q_CMD_EMAIL = "q.cmd.email";
    private static final String Q_CMD_CREW = "q.cmd.crew";
    private static final String Q_CMD_QUOTE = "q.cmd.quote";
    private static final String RK_EMAIL_SEND = "email.send";
    private static final String RK_CREW_TICKET = "crew.ticket";
    private static final String RK_QUOTE_GEN = "quote.gen";

    private static final AtomicBoolean CONNECTION_LOGGED = new AtomicBoolean(false);

    static final GenericContainer<?> RABBITMQ_CONTAINER = new GenericContainer<>(
            DockerImageName.parse("rabbitmq:4.3.6-management")
    ).withExposedPorts(RABBITMQ_AMQP_PORT);

    static {
        RABBITMQ_CONTAINER.start();
    }

    @DynamicPropertySource
    static void registerRabbitProperties(DynamicPropertyRegistry registry) {
        int mappedPort = RABBITMQ_CONTAINER.getMappedPort(RABBITMQ_AMQP_PORT);
        if (mappedPort == RABBITMQ_AMQP_PORT) {
            throw new IllegalStateException(
                    "Bloqueo de aislamiento: el puerto mapeado por Testcontainers no puede ser 5672."
            );
        }
        String host = RABBITMQ_CONTAINER.getHost();
        registry.add("spring.rabbitmq.addresses", () -> host + ":" + mappedPort);
        registry.add("spring.rabbitmq.host", () -> host);
        registry.add("spring.rabbitmq.port", () -> mappedPort);
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
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
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private InventoryReservationTaskRepository inventoryReservationTaskRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        assertIsolatedTestEnvironment();
        declareCompatibleNotifyTopologyOnEphemeralBroker();
        purgeEphemeralQueues();

        reset(catalogClient);
        lenient().doNothing().when(catalogClient).reserveInventory(anyLong(), anyList(), any());
        lenient().doNothing().when(catalogClient).reserveInventory(anyLong(), anyList());

        inventoryReservationTaskRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
        productionRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("1. Flujo completo SOLICITADO -> CONFIRMADO -> EN_MONTAJE -> EN_EJECUCION -> CERRADO: emite comandos exactos (Email, Crew y Quote) y contrato compatible con Notify")
    void fullLifecycleTransitions_shouldPublishExpectedEmailAndCrewCommandsAfterCommit() throws Exception {
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 12, 15, 20, 30);
        MDC.put("traceId", "trace-ep2-lifecycle-001");
        MDC.put("correlationId", "corr-ep2-lifecycle-001");

        try {
            // Paso 1: Creación en estado SOLICITADO -> NO debe publicar comandos en RabbitMQ
            ProductionResponseDTO created = productionService.createProduction(
                    new ProductionRequestDTO("org-ep2", "Festival Integración EP2", scheduledAt, "Movistar Arena"),
                    "productor-test"
            );
            assertEquals("SOLICITADO", created.status());
            assertEquals(0, getQueueMessageCount(Q_CMD_EMAIL), "SOLICITADO no debe emitir comando de email");
            assertEquals(0, getQueueMessageCount(Q_CMD_CREW), "SOLICITADO no debe emitir comando de cuadrilla");
            assertEquals(0, getQueueMessageCount(Q_CMD_QUOTE), "SOLICITADO no debe emitir comando de cotización");

            // Paso 2: Transición SOLICITADO -> CONFIRMADO -> Publica 1 comando Email y 0 de Cuadrilla/Cotización
            List<ProductionReservationItemDTO> items = List.of(new ProductionReservationItemDTO(1L, 2));
            ProductionResponseDTO confirmed = productionService.updateStatus(
                    created.id(),
                    new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO, items),
                    "productor-test"
            );
            assertEquals("CONFIRMADO", confirmed.status());
            assertEquals(1, getQueueMessageCount(Q_CMD_EMAIL), "CONFIRMADO debe publicar 1 comando en q.cmd.email");
            assertEquals(0, getQueueMessageCount(Q_CMD_CREW), "CONFIRMADO no debe publicar en q.cmd.crew");
            assertEquals(0, getQueueMessageCount(Q_CMD_QUOTE), "CONFIRMADO no debe publicar en q.cmd.quote");

            Message confirmedEmailMsg = receiveRequiredMessage(Q_CMD_EMAIL);
            JsonNode confirmedEmailJson = inspectAndAssertEnvelopeContract(
                    confirmedEmailMsg,
                    "SendProductionStatusEmail",
                    "trace-ep2-lifecycle-001",
                    "corr-ep2-lifecycle-001"
            );
            JsonNode confirmedPayload = confirmedEmailJson.get("payload");
            assertEquals(created.id().longValue(), confirmedPayload.get("productionId").asLong());
            assertEquals("org-ep2", confirmedPayload.get("organizerId").asText());
            assertEquals("Festival Integración EP2", confirmedPayload.get("productionName").asText());
            assertEquals("CONFIRMADO", confirmedPayload.get("status").asText());
            assertEquals("2026-12-15T20:30", confirmedPayload.get("scheduledAt").asText());
            assertEquals("Movistar Arena", confirmedPayload.get("location").asText());

            // Paso 3: Transición CONFIRMADO -> EN_MONTAJE -> Publica simultáneamente 1 Email y 1 Ticket de Cuadrilla
            ProductionResponseDTO inSetup = productionService.updateStatus(
                    created.id(),
                    new ProductionStatusUpdateDTO(ProductionStatus.EN_MONTAJE, null),
                    "productor-test"
            );
            assertEquals("EN_MONTAJE", inSetup.status());
            assertEquals(1, getQueueMessageCount(Q_CMD_EMAIL), "EN_MONTAJE debe publicar 1 comando en q.cmd.email");
            assertEquals(1, getQueueMessageCount(Q_CMD_CREW), "EN_MONTAJE debe publicar 1 comando en q.cmd.crew");
            assertEquals(0, getQueueMessageCount(Q_CMD_QUOTE), "EN_MONTAJE no debe publicar en q.cmd.quote");

            Message setupEmailMsg = receiveRequiredMessage(Q_CMD_EMAIL);
            JsonNode setupEmailJson = inspectAndAssertEnvelopeContract(
                    setupEmailMsg,
                    "SendProductionStatusEmail",
                    "trace-ep2-lifecycle-001",
                    "corr-ep2-lifecycle-001"
            );
            assertEquals("EN_MONTAJE", setupEmailJson.get("payload").get("status").asText());

            Message setupCrewMsg = receiveRequiredMessage(Q_CMD_CREW);
            JsonNode setupCrewJson = inspectAndAssertEnvelopeContract(
                    setupCrewMsg,
                    "GenerateCrewTicket",
                    "trace-ep2-lifecycle-001",
                    "corr-ep2-lifecycle-001"
            );
            JsonNode crewPayload = setupCrewJson.get("payload");
            assertEquals(created.id().longValue(), crewPayload.get("productionId").asLong());
            assertEquals("Festival Integración EP2", crewPayload.get("productionName").asText());
            assertEquals("2026-12-15T20:30", crewPayload.get("scheduledAt").asText());
            assertEquals("Movistar Arena", crewPayload.get("location").asText());
            assertEquals("EN_MONTAJE", crewPayload.get("status").asText());
            assertNull(crewPayload.get("organizerId"), "CrewTicketPayload V1 no incluye organizerId");

            // Paso 4: Transición EN_MONTAJE -> EN_EJECUCION -> NO debe publicar ni Email, ni Cuadrilla, ni Quote
            ProductionResponseDTO inExecution = productionService.updateStatus(
                    created.id(),
                    new ProductionStatusUpdateDTO(ProductionStatus.EN_EJECUCION, null),
                    "productor-test"
            );
            assertEquals("EN_EJECUCION", inExecution.status());
            assertEquals(0, getQueueMessageCount(Q_CMD_EMAIL), "EN_EJECUCION no debe emitir comando de email");
            assertEquals(0, getQueueMessageCount(Q_CMD_CREW), "EN_EJECUCION no debe emitir comando de cuadrilla");
            assertEquals(0, getQueueMessageCount(Q_CMD_QUOTE), "EN_EJECUCION no debe emitir comando de cotización");

            // Paso 5: Transición EN_EJECUCION -> CERRADO -> Publica simultáneamente 1 Email y 1 Cotización de Cierre (quote.gen)
            ProductionResponseDTO closed = productionService.updateStatus(
                    created.id(),
                    new ProductionStatusUpdateDTO(ProductionStatus.CERRADO, null),
                    "productor-test"
            );
            assertEquals("CERRADO", closed.status());
            assertEquals(1, getQueueMessageCount(Q_CMD_EMAIL), "CERRADO debe publicar 1 comando en q.cmd.email");
            assertEquals(0, getQueueMessageCount(Q_CMD_CREW), "CERRADO no debe publicar en q.cmd.crew");
            assertEquals(1, getQueueMessageCount(Q_CMD_QUOTE), "CERRADO debe publicar 1 comando en q.cmd.quote");

            Message closedEmailMsg = receiveRequiredMessage(Q_CMD_EMAIL);
            JsonNode closedEmailJson = inspectAndAssertEnvelopeContract(
                    closedEmailMsg,
                    "SendProductionStatusEmail",
                    "trace-ep2-lifecycle-001",
                    "corr-ep2-lifecycle-001"
            );
            assertEquals("CERRADO", closedEmailJson.get("payload").get("status").asText());
            assertNotEquals(
                    confirmedEmailJson.get("eventId").asText(),
                    closedEmailJson.get("eventId").asText(),
                    "Cada comando publicado debe generar un eventId único"
            );

            Message closedQuoteMsg = receiveRequiredMessage(Q_CMD_QUOTE);
            JsonNode closedQuoteJson = inspectAndAssertEnvelopeContract(
                    closedQuoteMsg,
                    "GenerateClosingQuote",
                    "trace-ep2-lifecycle-001",
                    "corr-ep2-lifecycle-001"
            );
            JsonNode quotePayload = closedQuoteJson.get("payload");
            assertEquals(created.id().longValue(), quotePayload.get("productionId").asLong());
            assertEquals("org-ep2", quotePayload.get("organizerId").asText());
            assertEquals("Festival Integración EP2", quotePayload.get("productionName").asText());
            assertEquals("CERRADO", quotePayload.get("status").asText());
            assertEquals("2026-12-15T20:30", quotePayload.get("scheduledAt").asText());
            assertEquals("Movistar Arena", quotePayload.get("location").asText());
            assertTrue(quotePayload.hasNonNull("closedAt") && !quotePayload.get("closedAt").asText().isBlank());
            assertNotEquals(
                    closedEmailJson.get("eventId").asText(),
                    closedQuoteJson.get("eventId").asText(),
                    "El comando de cotización debe tener un eventId único distinto del comando de email"
            );

            // Verificar preservación de outbox_events (PENDING) y republicación PDF sin Kafka disponible
            var outboxEvents = outboxEventRepository.findByProductionIdOrderByCreatedAtAsc(String.valueOf(created.id()));
            assertEquals(5, outboxEvents.size(), "Deben preservarse los 5 eventos de negocio en outbox_events aunque Kafka no esté disponible");
            assertTrue(outboxEvents.stream().allMatch(e -> "PENDING".equals(e.getStatus()) && e.getRetries() == 0),
                    "Todos los eventos en outbox_events deben permanecer en PENDING sin descartarse cuando el relay Kafka está inactivo");
            assertEquals(5, quotePayload.get("timeline").size(), "El payload quote.gen debe incluir las 5 transiciones reconstruidas desde outbox_events");

            boolean republished = productionService.republishQuoteCommand(created.id(), "productor-test");
            assertTrue(republished, "republishQuoteCommand debe publicar exitosamente en RabbitMQ sin Kafka disponible");
            assertEquals(1, getQueueMessageCount(Q_CMD_QUOTE), "La republicación debe encolar 1 comando en q.cmd.quote");
            receiveRequiredMessage(Q_CMD_QUOTE);

        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("2. Transición a CANCELADO: actualiza estado en BD pero no publica comandos en q.cmd.email, q.cmd.crew ni q.cmd.quote")
    void cancelTransition_shouldNotPublishEmailOrCrewCommands() {
        ProductionResponseDTO created = productionService.createProduction(
                new ProductionRequestDTO("org-ep2", "Evento Cancelado", LocalDateTime.of(2026, 11, 10, 19, 0), "Teatro Caupolicán"),
                "productor-test"
        );

        ProductionResponseDTO cancelled = productionService.updateStatus(
                created.id(),
                new ProductionStatusUpdateDTO(ProductionStatus.CANCELADO, null),
                "productor-test"
        );

        assertEquals("CANCELADO", cancelled.status());
        assertEquals(0, getQueueMessageCount(Q_CMD_EMAIL));
        assertEquals(0, getQueueMessageCount(Q_CMD_CREW));
        assertEquals(0, getQueueMessageCount(Q_CMD_QUOTE));
    }

    @Test
    @DisplayName("3. Rollback transaccional (fallo de reserva Catalog y rollback post-publishEvent): AFTER_COMMIT impide publicar en RabbitMQ")
    void transactionRollback_shouldPreventAnyRabbitMQCommandPublication() {
        ProductionResponseDTO created = productionService.createProduction(
                new ProductionRequestDTO("org-ep2", "Evento Rollback", LocalDateTime.of(2026, 12, 20, 21, 0), "Estadio Nacional"),
                "productor-test"
        );

        // Caso A: Fallo al reservar inventario en Catalog durante SOLICITADO -> CONFIRMADO
        lenient().doThrow(new InventoryReservationException("Stock insuficiente simulado en Catalog"))
                .when(catalogClient).reserveInventory(anyLong(), anyList(), any());
        lenient().doThrow(new InventoryReservationException("Stock insuficiente simulado en Catalog"))
                .when(catalogClient).reserveInventory(anyLong(), anyList());

        List<ProductionReservationItemDTO> items = List.of(new ProductionReservationItemDTO(1L, 99));
        assertThrows(
                InventoryReservationException.class,
                () -> productionService.updateStatus(
                        created.id(),
                        new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO, items),
                        "productor-test"
                )
        );

        assertEquals("SOLICITADO", productionService.getProductionById(created.id()).orElseThrow().status());
        assertEquals(0, getQueueMessageCount(Q_CMD_EMAIL), "Fallo de Catalog no debe publicar email");
        assertEquals(0, getQueueMessageCount(Q_CMD_CREW), "Fallo de Catalog no debe publicar ticket de cuadrilla");
        assertEquals(0, getQueueMessageCount(Q_CMD_QUOTE), "Fallo de Catalog no debe publicar cotización");

        // Caso B: Confirmar exitosamente, purgar cola, y forzar rollback en EN_MONTAJE después de publishEvent()
        reset(catalogClient);
        lenient().doNothing().when(catalogClient).reserveInventory(anyLong(), anyList(), any());
        lenient().doNothing().when(catalogClient).reserveInventory(anyLong(), anyList());
        productionService.updateStatus(
                created.id(),
                new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO, items),
                "productor-test"
        );
        purgeEphemeralQueues();

        transactionTemplate.executeWithoutResult(status -> {
            productionService.updateStatus(
                    created.id(),
                    new ProductionStatusUpdateDTO(ProductionStatus.EN_MONTAJE, null),
                    "productor-test"
            );
            status.setRollbackOnly();
        });

        assertEquals(
                "CONFIRMADO",
                productionService.getProductionById(created.id()).orElseThrow().status(),
                "El estado en BD debe revertirse a CONFIRMADO tras el rollback"
        );
        assertEquals(0, getQueueMessageCount(Q_CMD_EMAIL), "Rollback post-publishEvent no debe publicar email gracias a AFTER_COMMIT");
        assertEquals(0, getQueueMessageCount(Q_CMD_CREW), "Rollback post-publishEvent no debe publicar crew ticket gracias a AFTER_COMMIT");
        assertEquals(0, getQueueMessageCount(Q_CMD_QUOTE), "Rollback post-publishEvent no debe publicar quote gracias a AFTER_COMMIT");
    }

    private JsonNode inspectAndAssertEnvelopeContract(
            Message message,
            String expectedType,
            String expectedTraceId,
            String expectedCorrelationId
    ) throws Exception {
        assertNotNull(message, "Se esperaba recibir un mensaje desde la cola AMQP efímera");
        MessageProperties props = message.getMessageProperties();
        String rawJson = new String(message.getBody(), StandardCharsets.UTF_8);
        Map<String, Object> headers = props.getHeaders();

        log.info(
                "Mensaje AMQP real capturado de Productions: type={}, contentType={}, receivedDeliveryMode={}, headers={}, rawJson={}",
                expectedType,
                props.getContentType(),
                props.getReceivedDeliveryMode(),
                headers,
                rawJson
        );

        assertTrue(
                props.getContentType() != null && props.getContentType().startsWith(MessageProperties.CONTENT_TYPE_JSON),
                "El content-type debe ser application/json"
        );
        assertEquals(
                MessageDeliveryMode.PERSISTENT,
                props.getReceivedDeliveryMode(),
                "El mensaje publicado por RabbitMQCommandPublisher debe tener deliveryMode PERSISTENT"
        );
        assertEquals(
                "cl.duoc.eventomax.productions.messaging.common.MessageEnvelope",
                headers.get("__TypeId__"),
                "Jackson2JsonMessageConverter debe incluir el header __TypeId__ del MessageEnvelope de Productions"
        );
        assertFalse(
                headers.containsKey("__ContentTypeId__"),
                "Jackson2JsonMessageConverter no emite __ContentTypeId__ para un record genérico MessageEnvelope<T>"
        );

        JsonNode root = objectMapper.readTree(rawJson);
        assertEquals(expectedType, root.get("type").asText());
        assertTrue(root.hasNonNull("eventId") && !root.get("eventId").asText().isBlank());
        assertTrue(root.hasNonNull("timestamp") && !root.get("timestamp").asText().isBlank());
        assertEquals(expectedTraceId, root.get("traceId").asText());
        assertEquals(expectedCorrelationId, root.get("correlationId").asText());
        assertTrue(root.hasNonNull("payload") && root.get("payload").isObject());

        return root;
    }

    private Message receiveRequiredMessage(String queueName) {
        assertIsolatedTestEnvironment();
        Message message = rabbitTemplate.receive(queueName, 3_000L);
        assertNotNull(message, "No se recibió mensaje esperado en la cola " + queueName);
        return message;
    }

    private void assertIsolatedTestEnvironment() {
        assertTrue(RABBITMQ_CONTAINER.isRunning(), "El contenedor RabbitMQ efímero debe estar en ejecución");

        String containerId = RABBITMQ_CONTAINER.getContainerId();
        String containerName = RABBITMQ_CONTAINER.getContainerInfo().getName();
        int mappedPort = RABBITMQ_CONTAINER.getMappedPort(RABBITMQ_AMQP_PORT);
        String expectedHost = RABBITMQ_CONTAINER.getHost();
        String expectedAddresses = expectedHost + ":" + mappedPort;

        assertNotNull(containerId);
        assertFalse(
                containerName.contains(PERSISTENT_DEMO_CONTAINER_NAME)
                        || containerName.contains(PERSISTENT_EP2_CONTAINER_NAME),
                "Prohibido conectarse al contenedor persistente de RabbitMQ"
        );
        assertFalse(
                containerId.startsWith(PERSISTENT_DEMO_CONTAINER_ID_PREFIX)
                        || containerId.startsWith(PERSISTENT_EP2_CONTAINER_ID_PREFIX),
                "Prohibido reutilizar el contenedor persistente de demostración"
        );
        assertNotEquals(RABBITMQ_AMQP_PORT, mappedPort, "El puerto efímero no puede ser 5672");

        assertEquals(expectedHost, connectionFactory.getHost(), "ConnectionFactory debe apuntar al host efímero");
        assertEquals(mappedPort, connectionFactory.getPort(), "ConnectionFactory debe apuntar al puerto efímero");
        assertNotEquals(RABBITMQ_AMQP_PORT, connectionFactory.getPort(), "Prohibido conectarse al puerto 5672");
        assertEquals(expectedAddresses, environment.getProperty("spring.rabbitmq.addresses"));
        assertEquals(mappedPort, environment.getProperty("spring.rabbitmq.port", Integer.class));

        try (Connection dbConn = dataSource.getConnection()) {
            String jdbcUrl = dbConn.getMetaData().getURL();
            assertNotNull(jdbcUrl);
            assertTrue(
                    jdbcUrl.startsWith("jdbc:h2:mem:"),
                    "La prueba debe usar exclusivamente H2 en memoria y nunca PostgreSQL de demostración: " + jdbcUrl
            );
        } catch (Exception e) {
            throw new IllegalStateException("No fue posible verificar el aislamiento de DataSource H2", e);
        }

        if (CONNECTION_LOGGED.compareAndSet(false, true)) {
            log.info(
                    "Aislamiento Productions verificado: containerId={}, containerName={}, host={}, ephemeralPort={}, addresses={}",
                    containerId.substring(0, 12),
                    containerName,
                    connectionFactory.getHost(),
                    connectionFactory.getPort(),
                    expectedAddresses
            );
        }
    }

    private void declareCompatibleNotifyTopologyOnEphemeralBroker() {
        assertIsolatedTestEnvironment();
        DirectExchange directExchange = new DirectExchange(CMD_DIRECT_EXCHANGE, true, false);
        Queue emailQueue = QueueBuilder.durable(Q_CMD_EMAIL).build();
        Queue crewQueue = QueueBuilder.durable(Q_CMD_CREW).build();
        Queue quoteQueue = QueueBuilder.durable(Q_CMD_QUOTE).build();

        amqpAdmin.declareExchange(directExchange);
        amqpAdmin.declareQueue(emailQueue);
        amqpAdmin.declareQueue(crewQueue);
        amqpAdmin.declareQueue(quoteQueue);
        amqpAdmin.declareBinding(BindingBuilder.bind(emailQueue).to(directExchange).with(RK_EMAIL_SEND));
        amqpAdmin.declareBinding(BindingBuilder.bind(crewQueue).to(directExchange).with(RK_CREW_TICKET));
        amqpAdmin.declareBinding(BindingBuilder.bind(quoteQueue).to(directExchange).with(RK_QUOTE_GEN));
    }

    private void purgeEphemeralQueues() {
        assertIsolatedTestEnvironment();
        amqpAdmin.purgeQueue(Q_CMD_EMAIL, false);
        amqpAdmin.purgeQueue(Q_CMD_CREW, false);
        amqpAdmin.purgeQueue(Q_CMD_QUOTE, false);
    }

    private int getQueueMessageCount(String queueName) {
        assertIsolatedTestEnvironment();
        Properties props = amqpAdmin.getQueueProperties(queueName);
        if (props == null) {
            return 0;
        }
        Object count = props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
        if (count instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}

