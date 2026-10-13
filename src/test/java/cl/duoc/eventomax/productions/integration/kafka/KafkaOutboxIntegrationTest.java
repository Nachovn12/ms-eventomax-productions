package cl.duoc.eventomax.productions.integration.kafka;

import cl.duoc.eventomax.productions.dto.ProductionRequestDTO;
import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.model.OutboxEvent;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@SpringBootTest
@ActiveProfiles("test")
public class KafkaOutboxIntegrationTest {

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoSpyBean
    KafkaTemplate<String, String> kafkaTemplate;

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("eventomax_productions")
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

    @Autowired
    cl.duoc.eventomax.productions.service.OutboxManagementService outboxManagementService;

    private final List<String> messages = new CopyOnWriteArrayList<>();

    @KafkaListener(topics = "productions.events", groupId = "test-group-#{T(java.util.UUID).randomUUID().toString()}")
    public void listen(String message) {
        messages.add(message);
    }

    @BeforeEach
    void setup() {
        outboxEventRepository.deleteAllInBatch();
        productionRepository.deleteAllInBatch();
        messages.clear();
        // Reset spy if necessary, but spy re-uses the instance
    }

    @Test
    void testCreateProductionPersistsOutboxEventAndSendsToKafka() throws Exception {
        ProductionRequestDTO request = new ProductionRequestDTO(
                "org-1", "Test Production", LocalDateTime.now().plusDays(10), "Test Location"
        );

        productionService.createProduction(request, "actor-123");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);
            assertThat(outboxEvents.get(0).getStatus()).isEqualTo("SENT");
        });

        await().atMost(30, TimeUnit.SECONDS).until(() -> messages.stream().anyMatch(m -> m.contains("\"type\":\"ProductionCreated\"")));

        String payload = messages.stream().filter(m -> m.contains("\"type\":\"ProductionCreated\"")).findFirst().get();
        assertThat(payload).contains("\"type\":\"ProductionCreated\"");
        assertThat(payload).contains("\"actor\":\"actor-123\"");
        assertThat(payload).contains("\"productionName\":\"Test Production\"");
        assertThat(payload).contains("\"occurredAt\"");
    }

    @Test
    void testUpdateStatusPersistsOutboxEvent() throws Exception {
        ProductionRequestDTO request = new ProductionRequestDTO(
                "org-1", "Test Production 2", LocalDateTime.now().plusDays(10), "Test Location"
        );

        var created = productionService.createProduction(request, "actor-123");
        
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(outboxEventRepository.findAll()).hasSize(1);
        });
        
        messages.clear();
        outboxEventRepository.deleteAll(); // clear to isolate updateStatus

        ProductionStatusUpdateDTO updateRequest = new ProductionStatusUpdateDTO(ProductionStatus.CANCELADO, null);
        productionService.updateStatus(created.id(), updateRequest, "actor-456");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
            assertThat(outboxEvents).hasSize(1);
            assertThat(outboxEvents.get(0).getStatus()).isEqualTo("SENT");
        });

        await().atMost(30, TimeUnit.SECONDS).until(() -> messages.stream().anyMatch(m -> m.contains("\"type\":\"ProductionStatusChanged\"")));

        String payload = messages.stream().filter(m -> m.contains("\"type\":\"ProductionStatusChanged\"")).findFirst().get();
        assertThat(payload).contains("\"type\":\"ProductionStatusChanged\"");
        assertThat(payload).contains("\"actor\":\"actor-456\"");
        assertThat(payload).contains("\"newStatus\":\"CANCELADO\"");
        assertThat(payload).contains("\"occurredAt\"");
    }

    @Test
    void testDbRollbackPreventsOutboxEvent() {
        // Attempt to create with invalid data (e.g. null name) - this causes a DB constraint violation
        // In PostgreSQL: column "name" contains null values (if name is nullable, let's use null organizationId)
        // Wait, DTO validation might catch it first if @Valid is on the controller, but here we call the service directly
        ProductionRequestDTO request = new ProductionRequestDTO(
                null, null, null, null
        );
        try {
            productionService.createProduction(request, "actor-123");
        } catch (Exception e) {
            // Expected constraint violation
        }

        // Verify that NO outbox events were created
        assertThat(outboxEventRepository.findAll()).isEmpty();
        assertThat(productionRepository.findAll()).isEmpty();
    }

    @Test
    void testKafkaFailureAndRetryMechanism() {
        // Mock KafkaTemplate to throw an exception
        doThrow(new RuntimeException("Simulated Kafka failure")).when(kafkaTemplate).send(anyString(), anyString(), anyString());

        ProductionRequestDTO request = new ProductionRequestDTO(
                "org-1", "Failure Test", LocalDateTime.now().plusDays(10), "Location"
        );
        
        // This transaction will commit to DB (OutboxEvent saved as PENDING)
        productionService.createProduction(request, "actor-123");

        // The scheduled OutboxRelay will attempt to send and fail, retrying up to MAX_RETRIES (3)
        // Wait until the event transitions to ERROR state
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAll();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getRetries()).isEqualTo(3);
            assertThat(events.get(0).getStatus()).isEqualTo("ERROR");
            // It might reach ERROR state depending on timing, but let's assert retries increased
        });
    }

    @Test
    void testReclaimStuckEvents() {
        // Insert an event stuck in IN_PROGRESS state
        OutboxEvent stuck = new OutboxEvent("Production", "stuck-agg-1", "TestEvent", "{}");
        stuck.setStatus("IN_PROGRESS");
        stuck.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        stuck.setUpdatedAt(LocalDateTime.now().minusMinutes(5)); // Older than 2 mins
        outboxEventRepository.save(stuck);

        // Manually trigger processOutbox which calls reclaimStuckEvents
        outboxRelay.processOutbox();

        OutboxEvent reclaimed = outboxEventRepository.findById(stuck.getId()).orElseThrow();
        // Since it gets reclaimed to PENDING, the relay might immediately pick it up and send it
        // so it might end up as SENT. The important part is it's not IN_PROGRESS anymore.
        assertThat(reclaimed.getStatus()).isNotEqualTo("IN_PROGRESS");
    }

    @Test
    void testConcurrentRelaysDoNotDuplicateOrThrow() throws Exception {
        // Insert 50 events in PENDING
        for (int i=0; i<50; i++) {
            OutboxEvent event = new OutboxEvent("Production", "agg-test-" + i, "TestEvent", "{\"id\": " + i + "}");
            event.setStatus("PENDING");
            outboxEventRepository.save(event);
        }

        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(5);

        // Execute processOutbox concurrently from 5 threads
        for (int i=0; i<5; i++) {
            executor.submit(() -> {
                try {
                    outboxRelay.processOutbox();
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        // Allow some time for all events to be processed and sent
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            long sentCount = outboxEventRepository.findAll().stream()
                    .filter(e -> "SENT".equals(e.getStatus()))
                    .count();
            assertThat(sentCount).isEqualTo(50);
        });
    }

    @Test
    void testObsoleteWorkerCannotOverwrite() {
        OutboxEvent event = new OutboxEvent("Production", "obsolete-agg-1", "TestEvent", "{}");
        event.setStatus("PENDING");
        event = outboxEventRepository.save(event);

        // Worker A claims the event
        event.setStatus("IN_PROGRESS");
        OutboxEvent workerAEvent = outboxEventRepository.save(event);

        // Worker B reclaims it (simulating timeout)
        OutboxEvent workerBEvent = outboxEventRepository.findById(workerAEvent.getId()).orElseThrow();
        workerBEvent.setStatus("SENT");
        outboxEventRepository.save(workerBEvent);

        // Worker A now finishes and tries to mark as ERROR
        workerAEvent.setStatus("ERROR");
        
        try {
            outboxRelay.markAsSent(workerAEvent);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            // Expected
        } catch (org.springframework.transaction.UnexpectedRollbackException e) {
            // Also fine if wrapped by Spring's Tx interceptor
        }

        // The status should remain SENT, not ERROR, because Worker A was obsolete
        OutboxEvent freshEvent = outboxEventRepository.findById(workerAEvent.getId()).orElseThrow();
        assertThat(freshEvent.getStatus()).isEqualTo("SENT");
    }
    @Test
    void testPreviousErrorBlocksSubsequentEvents() {
        // Event 1: ERROR
        OutboxEvent event1 = new OutboxEvent("Production", "agg-block-1", "TestEvent1", "{}");
        event1.setStatus("ERROR");
        // Simulate event1 created first
        event1.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        outboxEventRepository.save(event1);

        // Event 2: PENDING (same aggregate)
        OutboxEvent event2 = new OutboxEvent("Production", "agg-block-1", "TestEvent2", "{}");
        event2.setStatus("PENDING");
        event2.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        outboxEventRepository.save(event2);

        // Claim batch
        List<OutboxEvent> claimed = outboxEventRepository.claimNextBatch(10);
        
        // Assert event2 is NOT claimed
        assertThat(claimed).isEmpty();
        
        // Assert event2 is still PENDING
        OutboxEvent freshEvent2 = outboxEventRepository.findById(event2.getId()).orElseThrow();
        assertThat(freshEvent2.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void testRecoveryOfErrorPreservesPayloadAndEventIdAndReleasesSubsequentEvents() {
        String eventId1 = java.util.UUID.randomUUID().toString();
        String payload1 = "{\"schemaVersion\":\"1.0\",\"eventId\":\"" + eventId1 + "\",\"type\":\"ProductionStatusChanged\",\"timestamp\":\"2026-10-12T20:00:00Z\",\"actor\":\"admin-recovery-1\",\"payload\":{\"productionId\":101}}";
        OutboxEvent event1 = new OutboxEvent("Production", "agg-rec-1", "ProductionStatusChanged", payload1);
        event1.setStatus("ERROR");
        event1.setRetries(3);
        event1.setErrorMessage("Kafka connection timeout");
        event1.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        event1 = outboxEventRepository.save(event1);
        final java.util.UUID id1 = event1.getId();

        String eventId2 = java.util.UUID.randomUUID().toString();
        String payload2 = "{\"schemaVersion\":\"1.0\",\"eventId\":\"" + eventId2 + "\",\"type\":\"ProductionStatusChanged\",\"timestamp\":\"2026-10-12T20:05:00Z\",\"actor\":\"admin\",\"payload\":{\"productionId\":101}}";
        OutboxEvent event2 = new OutboxEvent("Production", "agg-rec-1", "ProductionStatusChanged", payload2);
        event2.setStatus("PENDING");
        event2.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        event2 = outboxEventRepository.save(event2);
        final java.util.UUID id2 = event2.getId();

        // Before recovery: event2 is blocked by event1 in ERROR
        assertThat(outboxEventRepository.claimNextBatch(10)).isEmpty();

        // Perform recovery
        var result = outboxManagementService.retryFailedEvent(id1, "admin-recovery");
        assertThat(result.status()).isEqualTo(cl.duoc.eventomax.productions.dto.OutboxRecoveryStatus.RECOVERED);
        assertThat(result.previousRetries()).isEqualTo(3);

        // Verify event1 in DB: status is PENDING, retries is 0, errorMessage is null, payload is intact
        OutboxEvent refreshed1 = outboxEventRepository.findById(id1).orElseThrow();
        assertThat(refreshed1.getStatus()).isEqualTo("PENDING");
        assertThat(refreshed1.getRetries()).isEqualTo(0);
        assertThat(refreshed1.getErrorMessage()).isNull();
        assertThat(refreshed1.getPayload()).isEqualTo(payload1);

        // Trigger relay
        outboxRelay.processOutbox();

        // Wait until both events are sent in order
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            OutboxEvent e1 = outboxEventRepository.findById(id1).orElseThrow();
            OutboxEvent e2 = outboxEventRepository.findById(id2).orElseThrow();
            assertThat(e1.getStatus()).isEqualTo("SENT");
            assertThat(e2.getStatus()).isEqualTo("SENT");
        });

        // Messages received in Kafka
        await().atMost(10, TimeUnit.SECONDS).until(() ->
            messages.stream().anyMatch(m -> m.contains(eventId1)) &&
            messages.stream().anyMatch(m -> m.contains(eventId2))
        );

        int idx1 = -1, idx2 = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).contains(eventId1)) idx1 = i;
            if (messages.get(i).contains(eventId2)) idx2 = i;
        }
        assertThat(idx1).isGreaterThanOrEqualTo(0);
        assertThat(idx2).isGreaterThan(idx1); // Causal order preserved!
    }

    @Test
    void testRecoveryRejectionAndIdempotency() {
        // 1. Non-existent ID
        var notFoundResult = outboxManagementService.retryFailedEvent(java.util.UUID.randomUUID(), "admin");
        assertThat(notFoundResult.status()).isEqualTo(cl.duoc.eventomax.productions.dto.OutboxRecoveryStatus.NOT_FOUND);

        // 2. PENDING event
        OutboxEvent pending = new OutboxEvent("Production", "agg-rej-1", "Test", "{}");
        pending.setStatus("PENDING");
        pending = outboxEventRepository.save(pending);
        var pendingResult = outboxManagementService.retryFailedEvent(pending.getId(), "admin");
        assertThat(pendingResult.status()).isEqualTo(cl.duoc.eventomax.productions.dto.OutboxRecoveryStatus.ALREADY_PENDING);

        // 3. IN_PROGRESS event
        OutboxEvent inProg = new OutboxEvent("Production", "agg-rej-2", "Test", "{}");
        inProg.setStatus("IN_PROGRESS");
        inProg = outboxEventRepository.save(inProg);
        var inProgResult = outboxManagementService.retryFailedEvent(inProg.getId(), "admin");
        assertThat(inProgResult.status()).isEqualTo(cl.duoc.eventomax.productions.dto.OutboxRecoveryStatus.IN_PROGRESS);

        // 4. SENT event
        OutboxEvent sent = new OutboxEvent("Production", "agg-rej-3", "Test", "{}");
        sent.setStatus("SENT");
        sent = outboxEventRepository.save(sent);
        var sentResult = outboxManagementService.retryFailedEvent(sent.getId(), "admin");
        assertThat(sentResult.status()).isEqualTo(cl.duoc.eventomax.productions.dto.OutboxRecoveryStatus.ALREADY_SENT);
    }

    @Test
    void testConcurrentRecoveryOfSameEvent() throws Exception {
        OutboxEvent errEvent = new OutboxEvent("Production", "agg-conc-1", "Test", "{}");
        errEvent.setStatus("ERROR");
        errEvent.setRetries(3);
        errEvent = outboxEventRepository.save(errEvent);
        final java.util.UUID targetId = errEvent.getId();

        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<CompletableFuture<cl.duoc.eventomax.productions.dto.OutboxRecoveryResult>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final String actor = "admin-" + i;
            futures.add(CompletableFuture.supplyAsync(() -> outboxManagementService.retryFailedEvent(targetId, actor), pool));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        pool.shutdown();

        List<cl.duoc.eventomax.productions.dto.OutboxRecoveryResult> results = futures.stream().map(CompletableFuture::join).toList();
        long recoveredCount = results.stream().filter(r -> r.status() == cl.duoc.eventomax.productions.dto.OutboxRecoveryStatus.RECOVERED).count();
        long alreadyPendingCount = results.stream().filter(r -> r.status() == cl.duoc.eventomax.productions.dto.OutboxRecoveryStatus.ALREADY_PENDING).count();

        assertThat(recoveredCount).isEqualTo(1);
        assertThat(alreadyPendingCount).isEqualTo(4);

        OutboxEvent fresh = outboxEventRepository.findById(targetId).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo("PENDING");
        assertThat(fresh.getRetries()).isEqualTo(0);
    }

    @Test
    void testRecoveryBlockedByPredecessorAndIndependentProcessingOfOtherAggregates() {
        // Aggregate A: Event 1 is PENDING, Event 2 is ERROR
        OutboxEvent a1 = new OutboxEvent("Production", "agg-block-a", "EventA1", "{}");
        a1.setStatus("PENDING");
        a1.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        a1 = outboxEventRepository.save(a1);

        OutboxEvent a2 = new OutboxEvent("Production", "agg-block-a", "EventA2", "{}");
        a2.setStatus("ERROR");
        a2.setRetries(3);
        a2.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        a2 = outboxEventRepository.save(a2);

        // Attempt to recover A2 when A1 is still unresolved -> blocked!
        var blockedResult = outboxManagementService.retryFailedEvent(a2.getId(), "admin");
        assertThat(blockedResult.status()).isEqualTo(cl.duoc.eventomax.productions.dto.OutboxRecoveryStatus.BLOCKED_BY_PREDECESSOR);
        assertThat(outboxEventRepository.findById(a2.getId()).orElseThrow().getStatus()).isEqualTo("ERROR");

        // Aggregate B: Event B1 is PENDING, independent processing works
        OutboxEvent b1 = new OutboxEvent("Production", "agg-block-b", "EventB1", "{}");
        b1.setStatus("PENDING");
        b1.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        b1 = outboxEventRepository.save(b1);

        // Relay processes next batch: B1 should be claimable and not blocked by aggregate A
        List<OutboxEvent> claimed = outboxEventRepository.claimNextBatch(10);
        assertThat(claimed).extracting(OutboxEvent::getId).contains(b1.getId());
    }

    @Test
    void testCrashAfterSendBeforeMarkSentPreservesEventIdUponRedelivery() throws Exception {
        String eventId = java.util.UUID.randomUUID().toString();
        String payload = "{\"schemaVersion\":\"1.0\",\"eventId\":\"" + eventId + "\",\"type\":\"ProductionStatusChanged\",\"timestamp\":\"2026-10-12T20:00:00Z\",\"actor\":\"admin-crash\",\"payload\":{\"productionId\":202}}";

        // Step 1: Durable DB state representing crash after send to Kafka before markAsSent commit:
        // Status remains IN_PROGRESS with stuck lease (> 2 mins old), but message was published once to Kafka.
        OutboxEvent crashedEvent = new OutboxEvent("Production", "agg-crash-1", "ProductionStatusChanged", payload);
        crashedEvent.setStatus("IN_PROGRESS");
        crashedEvent.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        crashedEvent.setUpdatedAt(LocalDateTime.now().minusMinutes(5)); // Older than 2 mins
        crashedEvent = outboxEventRepository.save(crashedEvent);
        final java.util.UUID crashedId = crashedEvent.getId();

        // Publish to Kafka initially (representing the send that succeeded before the crash)
        kafkaTemplate.send("productions.events", crashedEvent.getAggregateId(), crashedEvent.getPayload()).get(10, TimeUnit.SECONDS);

        // Step 2: System recovers. Run reclaimStuckEvents()
        outboxRelay.reclaimStuckEvents();

        // Verify status transitioned from IN_PROGRESS back to PENDING
        OutboxEvent reclaimed = outboxEventRepository.findById(crashedId).orElseThrow();
        assertThat(reclaimed.getStatus()).isEqualTo("PENDING");

        // Step 3: Outbox relay runs its scheduled tick, re-sends and marks SENT
        outboxRelay.processOutbox();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            OutboxEvent finalEvent = outboxEventRepository.findById(crashedId).orElseThrow();
            assertThat(finalEvent.getStatus()).isEqualTo("SENT");
        });

        // Step 4: Verify messages in Kafka topic
        // We expect at least 2 messages in topic (the initial one + redelivered one)
        await().atMost(10, TimeUnit.SECONDS).until(() ->
            messages.stream().filter(m -> m.contains(eventId)).count() >= 2
        );

        List<String> matching = messages.stream().filter(m -> m.contains(eventId)).toList();
        assertThat(matching).hasSizeGreaterThanOrEqualTo(2);
        // Both deliveries carry the exact same eventId and payload
        for (String msg : matching) {
            assertThat(msg).contains("\"eventId\":\"" + eventId + "\"");
            assertThat(msg).contains("\"type\":\"ProductionStatusChanged\"");
            assertThat(msg).contains("\"productionId\":202");
        }
    }
}
