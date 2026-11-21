package cl.duoc.eventomax.productions.event;

import cl.duoc.eventomax.productions.controller.ProductionController;
import cl.duoc.eventomax.productions.dto.ExternalOrganizerType;
import cl.duoc.eventomax.productions.dto.OrganizerMode;
import cl.duoc.eventomax.productions.dto.ProductionRequestDTO;
import cl.duoc.eventomax.productions.dto.ProductionReservationItemDTO;
import cl.duoc.eventomax.productions.dto.ProductionResponseDTO;
import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.model.InventoryReservationTask;
import cl.duoc.eventomax.productions.model.OutboxEvent;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.InventoryReservationTaskRepository;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import cl.duoc.eventomax.productions.security.SecurityUtils;
import cl.duoc.eventomax.productions.service.EligibleOrganizerService;
import cl.duoc.eventomax.productions.service.InventoryReservationTaskService;
import cl.duoc.eventomax.productions.service.OrganizerContactService;
import cl.duoc.eventomax.productions.service.ProductionService;
import cl.duoc.eventomax.productions.service.ProductionStateCoordinator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActorDisplayNameAuditEventsTest {

    private static final String TENANT_ID = "f191bdc8-7926-4722-9ec0-cba663701940";
    private static final String USER_OID = "c3057bf6-9e39-4fa4-b042-d3932d49ebc0";
    private static final String CANONICAL_ACTOR = TENANT_ID + ":" + USER_OID;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // -------------------------------------------------------------------------
    // 1. Identity extraction (SecurityUtils)
    // -------------------------------------------------------------------------

    @Test
    void securityUtilsExtractsCanonicalActorAndDisplayNameFromValidatedJwt() {
        Jwt jwt = Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject("pairwise-sub-123")
                .claim("tid", TENANT_ID)
                .claim("oid", USER_OID)
                .claim("name", "  Ignacio Valeria Nunez  ")
                .claim("preferred_username", "ig.valeria@duocuc.cl")
                .build();

        assertThat(SecurityUtils.resolveOwnerId(jwt)).isEqualTo(CANONICAL_ACTOR);
        assertThat(SecurityUtils.resolveDisplayName(jwt)).isEqualTo("Ignacio Valeria Nunez");
    }

    @Test
    void securityUtilsReturnsNullDisplayNameWhenNameMissingOrBlankAndNeverFallsBackToEmailOrIds() {
        Jwt withoutName = Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject("pairwise-sub-123")
                .claim("tid", TENANT_ID)
                .claim("oid", USER_OID)
                .claim("preferred_username", "ig.valeria@duocuc.cl")
                .claim("email", "ig.valeria@duocuc.cl")
                .build();

        Jwt blankName = Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject("pairwise-sub-123")
                .claim("name", "   ")
                .build();

        assertThat(SecurityUtils.resolveDisplayName(withoutName)).isNull();
        assertThat(SecurityUtils.resolveDisplayName(blankName)).isNull();
    }

    @Test
    void securityUtilsTruncatesDisplayNameOver150CharsAndSupportsSubFallbackAndRejectsMissingIdentity() {
        Jwt longNameJwt = Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject("fallback-sub-only")
                .claim("name", "N".repeat(200))
                .build();

        assertThat(SecurityUtils.resolveOwnerId(longNameJwt)).isEqualTo("fallback-sub-only");
        assertThat(SecurityUtils.resolveDisplayName(longNameJwt)).hasSize(150);

        Jwt noIdentity = Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject(" ")
                .build();
        assertThatThrownBy(() -> SecurityUtils.resolveOwnerId(noIdentity))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> SecurityUtils.resolveOwnerId(null))
                .isInstanceOf(AccessDeniedException.class);
    }

    // -------------------------------------------------------------------------
    // 2. KafkaEventEnvelope serialization & compatibility with Audit Fase A
    // -------------------------------------------------------------------------

    @Test
    void envelopeSerializesOptionalActorDisplayNameAndOmitsWhenNullOrBlank() throws Exception {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-21T16:00:00Z");

        var withName = new KafkaEventEnvelope<>(
                eventId, "ProductionCreated", now, "trace-1", "corr-1",
                CANONICAL_ACTOR, "  Ignacio Valeria  ", "ms-eventomax-productions",
                java.util.Map.of("productionId", 9));
        JsonNode namedNode = objectMapper.readTree(objectMapper.writeValueAsString(withName));
        assertThat(namedNode.get("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(namedNode.get("actor").asText()).isEqualTo(CANONICAL_ACTOR);
        assertThat(namedNode.get("actorDisplayName").asText()).isEqualTo("Ignacio Valeria");
        assertThat(namedNode.get("source").asText()).isEqualTo("ms-eventomax-productions");
        assertThat(namedNode.get("traceId").asText()).isEqualTo("trace-1");
        assertThat(namedNode.get("correlationId").asText()).isEqualTo("corr-1");

        // Legacy 7-arg constructor & system event omit actorDisplayName
        var legacy = new KafkaEventEnvelope<>(
                eventId, "ProductionCreated", now, "trace-1", "corr-1",
                "system", java.util.Map.of("productionId", 9));
        JsonNode legacyNode = objectMapper.readTree(objectMapper.writeValueAsString(legacy));
        assertThat(legacyNode.get("actor").asText()).isEqualTo("system");
        assertThat(legacyNode.has("actorDisplayName")).isFalse();

        // Blank display name normalized to null and omitted
        var blank = new KafkaEventEnvelope<>(
                eventId, "ProductionCreated", now, "trace-1", "corr-1",
                CANONICAL_ACTOR, "   ", "ms-eventomax-productions",
                java.util.Map.of("productionId", 9));
        JsonNode blankNode = objectMapper.readTree(objectMapper.writeValueAsString(blank));
        assertThat(blankNode.has("actorDisplayName")).isFalse();
    }

    // -------------------------------------------------------------------------
    // 3. ProductionController: canonical actor & JWT-only display name
    // -------------------------------------------------------------------------

    @Test
    void controllerUpdateStatusUsesCanonicalActorAndJwtDisplayName() {
        ProductionService service = mock(ProductionService.class);
        OrganizerContactService contactService = mock(OrganizerContactService.class);
        ProductionController controller = new ProductionController(service, contactService);

        ProductionResponseDTO dto = new ProductionResponseDTO(
                9L, "org-1", "Evento", LocalDateTime.of(2027, 1, 1, 18, 0),
                "Auditorio", "EN_MONTAJE", LocalDateTime.now(), LocalDateTime.now());
        when(service.updateStatus(eq(9L), any(), eq(CANONICAL_ACTOR), eq("Ignacio Valeria"))).thenReturn(dto);

        Jwt jwt = Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject("pairwise-sub-ignored-when-oid-present")
                .claim("tid", TENANT_ID)
                .claim("oid", USER_OID)
                .claim("name", "Ignacio Valeria")
                .claim("roles", List.of("Productor"))
                .build();
        var auth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_Productor")));

        var resp = controller.updateStatus(9L, new ProductionStatusUpdateDTO(ProductionStatus.EN_MONTAJE), jwt, auth);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(service).updateStatus(eq(9L), any(), eq(CANONICAL_ACTOR), eq("Ignacio Valeria"));
    }

    @Test
    void controllerNewExternalCreationNeverUsesClientNameFromBodyAsActorDisplayName() {
        ProductionService service = mock(ProductionService.class);
        OrganizerContactService contactService = mock(OrganizerContactService.class);
        EligibleOrganizerService eligibleService = mock(EligibleOrganizerService.class);
        ProductionController controller = new ProductionController(service, contactService, eligibleService);

        ProductionResponseDTO dto = new ProductionResponseDTO(
                10L, "ext-123", "Gala Externa", LocalDateTime.of(2027, 5, 1, 20, 0),
                "Hotel", "SOLICITADO", LocalDateTime.now(), LocalDateTime.now());
        when(service.createProductionWithContact(any(), eq(CANONICAL_ACTOR), eq("Productor Real"), any()))
                .thenReturn(dto);

        Jwt jwt = Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject("sub-1")
                .claim("tid", TENANT_ID)
                .claim("oid", USER_OID)
                .claim("name", "Productor Real")
                .claim("roles", List.of("Productor"))
                .build();
        var auth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_Productor")));

        ProductionRequestDTO req = new ProductionRequestDTO(
                null, "Gala Externa", LocalDateTime.of(2027, 5, 1, 20, 0), "Hotel",
                null, null, OrganizerMode.EXTERNAL, ExternalOrganizerType.NEW_EXTERNAL,
                "Cliente Externo Falsificado", "cliente@empresa.cl");

        var resp = controller.create(req, jwt, auth);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        verify(service).createProductionWithContact(any(), eq(CANONICAL_ACTOR), eq("Productor Real"), any());
    }

    // -------------------------------------------------------------------------
    // 4. Full lifecycle Outbox propagation (Creation + Confirm + Montaje + Ejecucion + Cierre + Cancel)
    // -------------------------------------------------------------------------

    @Test
    void serviceAndCoordinatorPropagateActorDisplayNameAcrossAllLifecycleOutboxEvents() throws Exception {
        ProductionRepository repo = mock(ProductionRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CatalogClient catalogClient = mock(CatalogClient.class);
        OutboxEventRepository outboxRepo = mock(OutboxEventRepository.class);
        InventoryReservationTaskRepository taskRepo = mock(InventoryReservationTaskRepository.class);
        InventoryReservationTaskService taskService = mock(InventoryReservationTaskService.class);

        ProductionStateCoordinator coordinator = new ProductionStateCoordinator(
                repo, taskService, publisher, outboxRepo);
        ProductionService service = new ProductionService(
                repo, publisher, catalogClient, outboxRepo, taskRepo, taskService, coordinator);

        Production prod = new Production();
        prod.setId(55L);
        prod.setOrganizerId(CANONICAL_ACTOR);
        prod.setName("Festival Lifecycle");
        prod.setScheduledAt(LocalDateTime.of(2027, 6, 1, 19, 0));
        prod.setLocation("Teatro");
        prod.setStatus(ProductionStatus.SOLICITADO);

        when(repo.save(any(Production.class))).thenReturn(prod);
        when(repo.saveAndFlush(any(Production.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findById(55L)).thenReturn(Optional.of(prod));
        when(repo.findForUpdate(55L)).thenReturn(Optional.of(prod));

        InventoryReservationTask task = new InventoryReservationTask(55L, "[]", InventoryReservationTask.STATUS_PROCESSING);
        task.setReservationGeneration(1L);
        when(taskService.initOrUpdateIntent(eq(55L), any(), any())).thenReturn(task);
        when(taskService.getTaskByProductionId(55L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(task));
        when(taskService.markConfirmed(55L, 1L)).thenReturn(1);

        // 1. Creation (SELF via JWT)
        Jwt selfJwt = Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject("sub-self")
                .claim("tid", TENANT_ID)
                .claim("oid", USER_OID)
                .claim("name", "Ignacio Valeria")
                .build();
        service.createProduction(
                new ProductionRequestDTO(CANONICAL_ACTOR, "Festival Lifecycle", prod.getScheduledAt(), "Teatro", null, null, OrganizerMode.SELF),
                CANONICAL_ACTOR, selfJwt);

        // 2. SOLICITADO -> CONFIRMADO
        service.updateStatus(55L,
                new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO, List.of(new ProductionReservationItemDTO(1L, 2))),
                CANONICAL_ACTOR, "Ignacio Valeria");

        // 3. CONFIRMADO -> EN_MONTAJE
        service.updateStatus(55L,
                new ProductionStatusUpdateDTO(ProductionStatus.EN_MONTAJE),
                CANONICAL_ACTOR, "Productora Montaje");

        // 4. EN_MONTAJE -> EN_EJECUCION (without display name)
        service.updateStatus(55L,
                new ProductionStatusUpdateDTO(ProductionStatus.EN_EJECUCION),
                "sub-no-name", null);

        // 5. EN_EJECUCION -> CERRADO
        service.updateStatus(55L,
                new ProductionStatusUpdateDTO(ProductionStatus.CERRADO),
                CANONICAL_ACTOR, "Ignacio Valeria");

        // 6. Cancellation on another production
        Production prodCancel = new Production();
        prodCancel.setId(56L);
        prodCancel.setOrganizerId(CANONICAL_ACTOR);
        prodCancel.setName("Evento Cancelado");
        prodCancel.setScheduledAt(LocalDateTime.of(2027, 6, 2, 19, 0));
        prodCancel.setLocation("Sala B");
        prodCancel.setStatus(ProductionStatus.SOLICITADO);
        when(repo.findForUpdate(56L)).thenReturn(Optional.of(prodCancel));
        when(taskService.getTaskByProductionId(56L)).thenReturn(Optional.empty());
        service.updateStatus(56L,
                new ProductionStatusUpdateDTO(ProductionStatus.CANCELADO),
                CANONICAL_ACTOR, "Operador Cancelacion");

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo, org.mockito.Mockito.times(6)).save(outboxCaptor.capture());
        List<OutboxEvent> savedEvents = outboxCaptor.getAllValues();

        JsonNode created = objectMapper.readTree(savedEvents.get(0).getPayload());
        assertThat(created.get("type").asText()).isEqualTo("ProductionCreated");
        assertThat(created.get("actor").asText()).isEqualTo(CANONICAL_ACTOR);
        assertThat(created.get("actorDisplayName").asText()).isEqualTo("Ignacio Valeria");

        JsonNode confirmed = objectMapper.readTree(savedEvents.get(1).getPayload());
        assertThat(confirmed.get("type").asText()).isEqualTo("ProductionStatusChanged");
        assertThat(confirmed.get("actor").asText()).isEqualTo(CANONICAL_ACTOR);
        assertThat(confirmed.get("actorDisplayName").asText()).isEqualTo("Ignacio Valeria");
        assertThat(confirmed.get("payload").get("newStatus").asText()).isEqualTo("CONFIRMADO");

        JsonNode montaje = objectMapper.readTree(savedEvents.get(2).getPayload());
        assertThat(montaje.get("actorDisplayName").asText()).isEqualTo("Productora Montaje");
        assertThat(montaje.get("payload").get("newStatus").asText()).isEqualTo("EN_MONTAJE");

        JsonNode ejecucion = objectMapper.readTree(savedEvents.get(3).getPayload());
        assertThat(ejecucion.get("actor").asText()).isEqualTo("sub-no-name");
        assertThat(ejecucion.has("actorDisplayName")).isFalse();
        assertThat(ejecucion.get("payload").get("newStatus").asText()).isEqualTo("EN_EJECUCION");

        JsonNode cerrado = objectMapper.readTree(savedEvents.get(4).getPayload());
        assertThat(cerrado.get("actorDisplayName").asText()).isEqualTo("Ignacio Valeria");
        assertThat(cerrado.get("payload").get("newStatus").asText()).isEqualTo("CERRADO");

        JsonNode cancelado = objectMapper.readTree(savedEvents.get(5).getPayload());
        assertThat(cancelado.get("actorDisplayName").asText()).isEqualTo("Operador Cancelacion");
        assertThat(cancelado.get("payload").get("newStatus").asText()).isEqualTo("CANCELADO");
    }
}
