package cl.duoc.eventomax.productions.integration.kafka;

import cl.duoc.eventomax.productions.dto.ProductionRequestDTO;
import cl.duoc.eventomax.productions.messaging.kafka.OutboxRelay;
import cl.duoc.eventomax.productions.model.OutboxEvent;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import cl.duoc.eventomax.productions.service.ProductionService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class OutboxReliabilityIntegrationTest {
    static final String TOPIC = "emx92.configured.events";
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));
    static { DB.start(); KAFKA.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.datasource.driver-class-name", DB::getDriverClassName);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("eventomax.kafka.topic.productions", () -> TOPIC);
    }
    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired OutboxEventRepository repository;
    @Autowired ProductionRepository productions;
    @Autowired OutboxRelay relay;
    @Autowired ProductionService service;
    @BeforeEach void clear() throws Exception {
        repository.deleteAll(); productions.deleteAll();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            if (!admin.listTopics().names().get().contains(TOPIC)) admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get();
        }
    }
    @Test void equalTimestampsHaveStableOrderAndOnlyOneWorkerClaimsAggregate() throws Exception {
        var time = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<UUID> expected = new ArrayList<>();
        for (int i=0; i<10; i++) {
            var event = new OutboxEvent("Production", "same-aggregate", "Test", "{}");
            event.setCreatedAt(time);
            expected.add(repository.saveAndFlush(event).getId());
        }
        var executor = Executors.newFixedThreadPool(5);
        List<OutboxEvent> claimed = new ArrayList<>();
        try {
            var futures = new ArrayList<Future<List<OutboxEvent>>>();
            for (int i=0; i<5; i++) futures.add(executor.submit(relay::claimEventsBatch));
            for (var future : futures) claimed.addAll(future.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(claimed).extracting(OutboxEvent::getId).containsExactly(expected.getFirst());
        relay.markAsSent(claimed.getFirst());
        for (int i=1; i<expected.size(); i++) {
            var next = relay.claimEventsBatch();
            assertThat(next).extracting(OutboxEvent::getId).containsExactly(expected.get(i));
            relay.markAsSent(next.getFirst());
        }
        assertThat(relay.claimEventsBatch()).isEmpty();
    }
    @Test void staleWorkerCannotOverwriteAndErrorBlocksOnlyItsAggregate() {
        var first = repository.saveAndFlush(new OutboxEvent("Production", "one", "Test", "{}"));
        var stale = relay.claimEventsBatch().getFirst();
        var current = repository.findById(first.getId()).orElseThrow();
        current.setStatus("ERROR"); repository.saveAndFlush(current);
        assertThatThrownBy(() -> relay.markAsSent(stale))
            .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
        repository.saveAndFlush(new OutboxEvent("Production", "one", "Later", "{}"));
        var other = repository.saveAndFlush(new OutboxEvent("Production", "two", "Other", "{}"));
        assertThat(relay.claimEventsBatch()).extracting(OutboxEvent::getId).containsExactly(other.getId());
        assertThat(repository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo("ERROR");
    }
    @Test void concurrentRelaysPublishOnceToConfiguredTopicAndPreserveTrace() throws Exception {
        List<String> eventIds = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(), "group.id", "reliability-"+UUID.randomUUID(),
                "auto.offset.reset", "earliest", "enable.auto.commit", false), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            MDC.put("traceId", "trace-real-producer-test"); MDC.put("correlationId", "correlation-test");
            try {
                for (int i=0; i<12; i++) service.createProduction(new ProductionRequestDTO("org", "concurrency-"+i, LocalDateTime.now().plusDays(1), "Santiago"), "authenticated-test-actor");
            } finally { MDC.clear(); }
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (var event : repository.findAll()) eventIds.add(mapper.readTree(event.getPayload()).get("eventId").asText());
            var executor = Executors.newFixedThreadPool(4);
            try {
                var futures = new ArrayList<Future<?>>();
                for (int i=0; i<4; i++) futures.add(executor.submit(relay::processOutbox));
                for (var future : futures) future.get(30, TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
            relay.processOutbox(); // SENT events must not be sent by another tick.
            Map<String,Integer> counts = new HashMap<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            long quietDeadline = Long.MAX_VALUE;
            while (System.nanoTime()<deadline && System.nanoTime()<quietDeadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    var json=mapper.readTree(record.value());
                    String id=json.get("eventId").asText();
                    if (eventIds.contains(id)) {
                        counts.merge(id,1,Integer::sum);
                        assertThat(json.get("traceId").asText()).isEqualTo("trace-real-producer-test");
                        assertThat(json.get("correlationId").asText()).isEqualTo("correlation-test");
                        assertThat(record.key()).isEqualTo(json.get("payload").get("productionId").asText());
                    }
                }
                if (counts.size()==12 && quietDeadline==Long.MAX_VALUE) quietDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            }
            assertThat(counts).hasSize(12);
            assertThat(counts.values()).containsOnly(1);
            assertThat(repository.findAll()).allMatch(e -> e.getStatus().equals("SENT") && e.getRetries()==0);
        }
    }
}
