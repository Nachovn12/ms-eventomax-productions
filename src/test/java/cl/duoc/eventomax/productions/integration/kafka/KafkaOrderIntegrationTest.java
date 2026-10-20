package cl.duoc.eventomax.productions.integration.kafka;

import cl.duoc.eventomax.productions.dto.ProductionRequestDTO;
import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import cl.duoc.eventomax.productions.messaging.kafka.OutboxRelay;
import cl.duoc.eventomax.productions.service.ProductionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

@SpringBootTest
@ActiveProfiles("test")
public class KafkaOrderIntegrationTest {

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    CatalogClient catalogClient; // To prevent actual calls during CONFIRMADO

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("eventomax_productions_order")
            .withUsername("testuser")
            .withPassword("testpass");

    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    static {
        postgres.start();
        kafka.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("eventomax.outbox.poll-delay-ms", () -> "2000");
        registry.add("eventomax.outbox.initial-delay-ms", () -> "0");
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("eventomax.security.jwt.audience", () -> "dummy");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://dummy");
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    @Autowired
    ProductionService productionService;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    ProductionRepository productionRepository;

    @Autowired
    OutboxRelay outboxRelay;

    private final List<String> messages = new CopyOnWriteArrayList<>();

    @KafkaListener(topics = "productions.events", groupId = "order-group-#{T(java.util.UUID).randomUUID().toString()}")
    public void listen(String message) {
        messages.add(message);
    }

    @BeforeEach
    void setup() {
        outboxEventRepository.deleteAll();
        productionRepository.deleteAll();
        messages.clear();
        doNothing().when(catalogClient).reserveInventory(any(), any());
    }

    @Test
    void testCausalOrderingAndJsonFormat() throws Exception {
        // 1. Create Production (Status = SOLICITADO)
        ProductionRequestDTO request = new ProductionRequestDTO(
                "org-1", "Test JSON and Order", LocalDateTime.now().plusDays(10), "Test Location"
        );
        var created = productionService.createProduction(request, "actor-abc");

        // 2. Change SOLICITADO -> CONFIRMADO
        productionService.updateStatus(created.id(), new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO, java.util.List.of(new cl.duoc.eventomax.productions.dto.ProductionReservationItemDTO(1L, 10))), "actor-def");

        // 3. Change CONFIRMADO -> EN_MONTAJE
        productionService.updateStatus(created.id(), new ProductionStatusUpdateDTO(ProductionStatus.EN_MONTAJE, null), "actor-ghi");

        // Wait for Relay to send
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(outboxEventRepository.findAll().stream().allMatch(e -> e.getStatus().equals("SENT"))).isTrue();
        });

        // 4. Consume real messages
        await().atMost(10, TimeUnit.SECONDS).until(() -> messages.size() >= 3);

        // Print to log for analysis
        System.out.println("JSON 1: " + messages.get(0));
        System.out.println("JSON 2: " + messages.get(1));
        System.out.println("JSON 3: " + messages.get(2));

        // 5. Verify Ordering and strict JSON structure
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        com.fasterxml.jackson.databind.JsonNode json1 = mapper.readTree(messages.get(0));
        com.fasterxml.jackson.databind.JsonNode json2 = mapper.readTree(messages.get(1));
        com.fasterxml.jackson.databind.JsonNode json3 = mapper.readTree(messages.get(2));

        // Event 1
        assertThat(json1.get("type").asText()).isEqualTo("ProductionCreated");
        assertThat(json1.get("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(json1.get("actor").asText()).isEqualTo("actor-abc");
        assertThat(json1.has("eventId")).isTrue();
        assertThat(json1.get("timestamp").asText()).matches(".*Z"); // ISO UTC string
        assertThat(json1.has("traceId")).isTrue();
        
        com.fasterxml.jackson.databind.JsonNode payload1 = json1.get("payload");
        assertThat(payload1.get("status").asText()).isEqualTo("SOLICITADO");
        assertThat(payload1.get("productionId").asLong()).isEqualTo(created.id());
        assertThat(payload1.get("occurredAt").asText()).matches(".*Z");
        assertThat(payload1.get("scheduledAt").asText()).doesNotContain("Z");

        // Event 2
        assertThat(json2.get("type").asText()).isEqualTo("ProductionStatusChanged");
        assertThat(json2.get("actor").asText()).isEqualTo("actor-def");
        com.fasterxml.jackson.databind.JsonNode payload2 = json2.get("payload");
        assertThat(payload2.get("previousStatus").asText()).isEqualTo("SOLICITADO");
        assertThat(payload2.get("newStatus").asText()).isEqualTo("CONFIRMADO");
        assertThat(payload2.get("occurredAt").asText()).matches(".*Z");

        // Event 3
        assertThat(json3.get("type").asText()).isEqualTo("ProductionStatusChanged");
        assertThat(json3.get("actor").asText()).isEqualTo("actor-ghi");
        com.fasterxml.jackson.databind.JsonNode payload3 = json3.get("payload");
        assertThat(payload3.get("previousStatus").asText()).isEqualTo("CONFIRMADO");
        assertThat(payload3.get("newStatus").asText()).isEqualTo("EN_MONTAJE");
        assertThat(payload3.get("occurredAt").asText()).matches(".*Z");
    }
}
