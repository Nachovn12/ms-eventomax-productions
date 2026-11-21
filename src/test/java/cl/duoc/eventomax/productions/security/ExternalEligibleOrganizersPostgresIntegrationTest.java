package cl.duoc.eventomax.productions.security;

import cl.duoc.eventomax.productions.dto.DirectorySyncStatus;
import cl.duoc.eventomax.productions.dto.EligibleOrganizerSource;
import cl.duoc.eventomax.productions.dto.ExternalOrganizerType;
import cl.duoc.eventomax.productions.dto.OrganizerMode;
import cl.duoc.eventomax.productions.dto.ProductionRequestDTO;
import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.integration.graph.MicrosoftGraphEligibleOrganizerProvider;
import cl.duoc.eventomax.productions.model.OrganizerContact;
import cl.duoc.eventomax.productions.repository.OrganizerContactRepository;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import cl.duoc.eventomax.productions.service.DirectoryFetchResult;
import cl.duoc.eventomax.productions.service.EligibleOrganizerCandidate;
import cl.duoc.eventomax.productions.service.ExternalDirectoryUnavailableException;
import cl.duoc.eventomax.productions.service.ProductionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración reales contra PostgreSQL 16 (Testcontainers) para Etapa 2:
 * GET /api/organizers/eligible y creación EXTERNAL (ENTRA_ORGANIZER, EXISTING_EXTERNAL, NEW_EXTERNAL).
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExternalEligibleOrganizersPostgresIntegrationTest {

    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.datasource.driver-class-name", DB::getDriverClassName);
    }

    static final String TID = "f191bdc8-7926-4722-9ec0-cba663701940";
    static final String OID_PRODUCTOR = "c3057bf6-9e39-4fa4-b042-d3932d49ebc0";
    static final String OID_ENTRA_ORG = "e5660fea-f5dd-4974-abb0-47e92f389d63";
    static final String OID_UNAUTHORIZED = "99999999-9999-4999-8999-999999999999";
    static final String ENTRA_ORG_ID = TID + ":" + OID_ENTRA_ORG;

    @Autowired
    MockMvc mvc;

    @Autowired
    ProductionService productionService;

    @Autowired
    ProductionRepository productionRepository;

    @Autowired
    OrganizerContactRepository contactRepository;

    @MockitoSpyBean
    OutboxEventRepository outboxEventRepository;

    @MockitoBean
    MicrosoftGraphEligibleOrganizerProvider graphProvider;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    CatalogClient catalogClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        reset(outboxEventRepository, graphProvider);
        outboxEventRepository.deleteAll();
        productionRepository.deleteAll();
        contactRepository.deleteAll();

        when(graphProvider.source()).thenReturn(EligibleOrganizerSource.ENTRA_ORGANIZER);
        when(graphProvider.fetchEligibleOrganizers()).thenReturn(
                new DirectoryFetchResult(List.of(), DirectorySyncStatus.DISABLED, false));
        doThrow(new ExternalDirectoryUnavailableException("Graph desactivado por defecto"))
                .when(graphProvider).verifyEligibleOrganizer(any());
    }

    private JwtRequestPostProcessor tokenFor(String oid, List<String> roles) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("SCOPE_access_as_user"));
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return jwt().authorities(authorities.toArray(SimpleGrantedAuthority[]::new))
                .jwt(j -> j.subject("sub-" + oid)
                        .issuer("https://login.microsoftonline.com/" + TID + "/v2.0")
                        .claim("tid", TID)
                        .claim("oid", oid)
                        .claim("roles", roles)
                        .claim("scp", "access_as_user"));
    }

    @Test
    void eligibleEndpoint_enforcesRolesAndReturnsPaginatedFilteredResults() throws Exception {
        contactRepository.saveAndFlush(new OrganizerContact("org-productora-sur", "contacto@sur.cl", "Productora Sur", null, null, "admin"));
        contactRepository.saveAndFlush(new OrganizerContact("org-norte", "info@norte.cl", "Eventos Norte", null, null, "admin"));
        when(graphProvider.fetchEligibleOrganizers()).thenReturn(new DirectoryFetchResult(
                List.of(new EligibleOrganizerCandidate(ENTRA_ORG_ID, "Benjamin Espinoza", EligibleOrganizerSource.ENTRA_ORGANIZER, OID_ENTRA_ORG, "benja@duocuc.cl")),
                DirectorySyncStatus.OK, true));

        // Sin JWT -> 401
        mvc.perform(get("/api/organizers/eligible")).andExpect(status().isUnauthorized());

        // Organizador y Auditor -> 403
        mvc.perform(get("/api/organizers/eligible").with(tokenFor(OID_ENTRA_ORG, List.of("Organizador"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/organizers/eligible").with(tokenFor(OID_UNAUTHORIZED, List.of("Auditor"))))
                .andExpect(status().isForbidden());

        // Productor -> 200 con paginación, límite máximo 50 y correo enmascarado
        mvc.perform(get("/api/organizers/eligible")
                        .param("size", "500")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size", is(50)))
                .andExpect(jsonPath("$.total", is(3)))
                .andExpect(jsonPath("$.directorySyncStatus", is("OK")))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].displayName", is("Benjamin Espinoza")))
                .andExpect(jsonPath("$.items[0].source", is("ENTRA_ORGANIZER")))
                .andExpect(jsonPath("$.items[0].hasRegisteredContact", is(false)))
                .andExpect(jsonPath("$.items[2].displayName", is("Productora Sur")))
                .andExpect(jsonPath("$.items[2].maskedEmail", is("co***@sur.cl")));

        // Admin con filtro q -> 200
        mvc.perform(get("/api/organizers/eligible")
                        .param("q", "Sur")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.items[0].organizerId", is("org-productora-sur")));
    }

    @Test
    void externalCaseA_entraOrganizer_authorizedSucceedsAndProvisionsContactWithoutOverwriting() throws Exception {
        EligibleOrganizerCandidate verified = new EligibleOrganizerCandidate(
                ENTRA_ORG_ID, "Benjamin Espinoza", EligibleOrganizerSource.ENTRA_ORGANIZER, OID_ENTRA_ORG, "benja@duocuc.cl");
        doReturn(Optional.of(verified)).when(graphProvider).verifyEligibleOrganizer(ENTRA_ORG_ID);

        String body = """
                {
                  "organizerMode": "EXTERNAL",
                  "externalOrganizerType": "ENTRA_ORGANIZER",
                  "organizerId": "%s",
                  "name": "Gala Organizador Entra",
                  "scheduledAt": "2035-08-10T19:00:00",
                  "location": "Teatro Municipal"
                }
                """.formatted(ENTRA_ORG_ID);

        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Admin", "Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizerId", is(ENTRA_ORG_ID)));

        OrganizerContact contact = contactRepository.findByOrganizerIdAndActiveTrue(ENTRA_ORG_ID).orElseThrow();
        assertThat(contact.getEmail()).isEqualTo("benja@duocuc.cl");
        assertThat(contact.getUserOid()).isEqualTo(OID_ENTRA_ORG);
        assertThat(outboxEventRepository.count()).isEqualTo(1);

        // No sobrescribe si el organizador personalizó su correo
        contact.setEmail("custom-org@empresa.cl");
        contactRepository.saveAndFlush(contact);

        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        assertThat(contactRepository.findByOrganizerIdAndActiveTrue(ENTRA_ORG_ID).orElseThrow().getEmail())
                .isEqualTo("custom-org@empresa.cl");
    }

    @Test
    void externalCaseA_entraOrganizer_rejectsUnauthorizedOrManualEmailOrDisabledGraph() throws Exception {
        String unauthorizedId = TID + ":" + OID_UNAUTHORIZED;
        doReturn(Optional.empty()).when(graphProvider).verifyEligibleOrganizer(unauthorizedId);

        // Usuario no elegible -> 403
        String unauthBody = """
                {"organizerMode":"EXTERNAL","externalOrganizerType":"ENTRA_ORGANIZER","organizerId":"%s",
                 "name":"No autorizado","scheduledAt":"2035-08-10T19:00:00","location":"Santiago"}
                """.formatted(unauthorizedId);
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unauthBody))
                .andExpect(status().isForbidden());

        // Correo arbitrario para usuario Entra -> 409 Conflict
        String emailOverrideBody = """
                {"organizerMode":"EXTERNAL","externalOrganizerType":"ENTRA_ORGANIZER","organizerId":"%s",
                 "externalClientEmail":"atacante@evil.cl","name":"Override","scheduledAt":"2035-08-10T19:00:00","location":"Santiago"}
                """.formatted(ENTRA_ORG_ID);
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emailOverrideBody))
                .andExpect(status().isConflict());

        // Graph desactivado / no disponible -> 503 Service Unavailable (fail-closed)
        String unavailableBody = """
                {"organizerMode":"EXTERNAL","externalOrganizerType":"ENTRA_ORGANIZER","organizerId":"%s",
                 "name":"Graph off","scheduledAt":"2035-08-10T19:00:00","location":"Santiago"}
                """.formatted(ENTRA_ORG_ID);
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unavailableBody))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void externalCaseB_existingExternal_reusesContactAndBlocksEmailOverwriteOrCanonicalHijack() throws Exception {
        contactRepository.saveAndFlush(new OrganizerContact("org-historico-01", "cliente@historico.cl", "Cliente Historico", null, null, "admin"));

        String okBody = """
                {"organizerMode":"EXTERNAL","externalOrganizerType":"EXISTING_EXTERNAL","organizerId":"org-historico-01",
                 "name":"Evento Historico","scheduledAt":"2035-08-10T19:00:00","location":"Valparaiso"}
                """;
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(okBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizerId", is("org-historico-01")));

        // Intentar cambiar el correo del cliente existente -> 409 Conflict
        String overwriteBody = """
                {"organizerMode":"EXTERNAL","externalOrganizerType":"EXISTING_EXTERNAL","organizerId":"org-historico-01",
                 "externalClientEmail":"otro@correo.cl","name":"Override","scheduledAt":"2035-08-10T19:00:00","location":"Valparaiso"}
                """;
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overwriteBody))
                .andExpect(status().isConflict());

        // Intentar usar identidad canónica Entra como EXISTING_EXTERNAL -> 403 Forbidden
        String canonicalHijack = """
                {"organizerMode":"EXTERNAL","externalOrganizerType":"EXISTING_EXTERNAL","organizerId":"%s",
                 "name":"Hijack","scheduledAt":"2035-08-10T19:00:00","location":"Valparaiso"}
                """.formatted(ENTRA_ORG_ID);
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(canonicalHijack))
                .andExpect(status().isForbidden());

        // Cliente inexistente -> 404 Not Found
        String missingBody = """
                {"organizerMode":"EXTERNAL","externalOrganizerType":"EXISTING_EXTERNAL","organizerId":"org-no-existe",
                 "name":"Missing","scheduledAt":"2035-08-10T19:00:00","location":"Valparaiso"}
                """;
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingBody))
                .andExpect(status().isNotFound());
    }

    @Test
    void externalCaseC_newExternal_generatesOpaqueExtUuid_validatesFields_andRollsBackAtomicallyOnFailure() throws Exception {
        String createBody = """
                {
                  "organizerMode": "EXTERNAL",
                  "externalOrganizerType": "NEW_EXTERNAL",
                  "externalClientName": "Festival Costero SpA",
                  "externalClientEmail": "produccion@festivalcostero.cl",
                  "name": "Festival Costero 2035",
                  "scheduledAt": "2035-09-20T20:00:00",
                  "location": "Concepcion"
                }
                """;

        String responseJson = mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizerId", startsWith("ext-")))
                .andReturn().getResponse().getContentAsString();

        String generatedId = objectMapper.readTree(responseJson).get("organizerId").asText();
        OrganizerContact createdContact = contactRepository.findByOrganizerIdAndActiveTrue(generatedId).orElseThrow();
        assertThat(createdContact.getEmail()).isEqualTo("produccion@festivalcostero.cl");
        assertThat(createdContact.getContactName()).isEqualTo("Festival Costero SpA");
        assertThat(createdContact.getUserOid()).isNull();

        // Rechaza organizerId arbitrario en NEW_EXTERNAL -> 400
        String arbitraryIdBody = """
                {"organizerMode":"EXTERNAL","externalOrganizerType":"NEW_EXTERNAL","organizerId":"org-manual-robado",
                 "externalClientName":"Cliente","externalClientEmail":"ok@cliente.cl",
                 "name":"Evento","scheduledAt":"2035-09-20T20:00:00","location":"Concepcion"}
                """;
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arbitraryIdBody))
                .andExpect(status().isBadRequest());

        // Rechaza falta de correo en NEW_EXTERNAL -> 400
        String missingEmailBody = """
                {"organizerMode":"EXTERNAL","externalOrganizerType":"NEW_EXTERNAL",
                 "externalClientName":"Cliente Sin Correo",
                 "name":"Evento","scheduledAt":"2035-09-20T20:00:00","location":"Concepcion"}
                """;
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingEmailBody))
                .andExpect(status().isBadRequest());

        // Rollback atómico en PostgreSQL 16 si falla Outbox -> cero contactos huérfanos
        outboxEventRepository.deleteAll();
        productionRepository.deleteAll();
        contactRepository.deleteAll();
        doThrow(new RuntimeException("Simulated Outbox failure")).when(outboxEventRepository).save(any());

        String rollbackId = "ext-rollback-check";
        ProductionRequestDTO req = new ProductionRequestDTO(
                rollbackId, "Rollback External", LocalDateTime.now().plusDays(15), "Santiago",
                null, null, OrganizerMode.EXTERNAL, ExternalOrganizerType.NEW_EXTERNAL,
                "Cliente Rollback", "rollback@cliente.cl");
        var provision = new ProductionService.ContactProvisionRequest(
                rollbackId, "rollback@cliente.cl", "Cliente Rollback", null);

        assertThatThrownBy(() -> productionService.createProductionWithContact(req, TID + ":" + OID_PRODUCTOR, provision))
                .isInstanceOf(RuntimeException.class);

        assertThat(productionRepository.count()).isZero();
        assertThat(contactRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void legacyAntiBypass_validatesForeignCanonicalIdsAndRejectsContradictoryExternalFields() throws Exception {
        String ownCanonical = TID + ":" + OID_PRODUCTOR;
        String foreignEligible = ENTRA_ORG_ID;
        String foreignUnauthorized = TID + ":" + OID_UNAUTHORIZED;

        // 1. Legacy Productor + propia identidad canónica -> 201 OK
        String ownLegacy = """
                {"organizerId":"%s","name":"Legacy Propia","scheduledAt":"2035-08-10T19:00:00","location":"Santiago"}
                """.formatted(ownCanonical);
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ownLegacy))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizerId", is(ownCanonical)));

        // 2. Legacy Productor + identidad canónica ajena con Graph desactivado -> 503 fail-closed
        String foreignLegacy = """
                {"organizerId":"%s","name":"Legacy Ajena","scheduledAt":"2035-08-10T19:00:00","location":"Santiago"}
                """.formatted(foreignEligible);
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(foreignLegacy))
                .andExpect(status().isServiceUnavailable());

        // 3. Legacy Productor + identidad canónica ajena no elegible -> 403 Forbidden
        doReturn(Optional.empty()).when(graphProvider).verifyEligibleOrganizer(foreignUnauthorized);
        String unauthLegacy = """
                {"organizerId":"%s","name":"Legacy No Elegible","scheduledAt":"2035-08-10T19:00:00","location":"Santiago"}
                """.formatted(foreignUnauthorized);
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unauthLegacy))
                .andExpect(status().isForbidden());

        // 4. Legacy Productor + identidad canónica ajena elegible -> 201 Created
        EligibleOrganizerCandidate verified = new EligibleOrganizerCandidate(
                foreignEligible, "Benjamin Espinoza", EligibleOrganizerSource.ENTRA_ORGANIZER, OID_ENTRA_ORG, "benja@duocuc.cl");
        doReturn(Optional.of(verified)).when(graphProvider).verifyEligibleOrganizer(foreignEligible);
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(foreignLegacy))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizerId", is(foreignEligible)));

        // 5. SELF con campos EXTERNAL contradictorios -> 400 Bad Request
        String selfWithExternal = """
                {"organizerMode":"SELF","externalOrganizerType":"NEW_EXTERNAL",
                 "name":"Contradictorio SELF","scheduledAt":"2035-08-10T19:00:00","location":"Santiago"}
                """;
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfWithExternal))
                .andExpect(status().isBadRequest());

        // 6. Legacy (organizerMode=null) con campos EXTERNAL contradictorios -> 400 Bad Request
        String legacyWithExternal = """
                {"organizerId":"org-alias","externalClientEmail":"x@cliente.cl",
                 "name":"Contradictorio Legacy","scheduledAt":"2035-08-10T19:00:00","location":"Santiago"}
                """;
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacyWithExternal))
                .andExpect(status().isBadRequest());
    }

    @Test
    void producerOnlySelfContact_allowedOnlyForOwnCanonicalId_blocksForeignAndOidTampering_preservesAdminAndOrganizer() throws Exception {
        String producerCanonical = TID + ":" + OID_PRODUCTOR;

        // 1. Productor exclusivo registra su propio contacto canónico -> 200 OK
        String selfContactPayload = "{\"email\":\"productor.propio@duocuc.cl\",\"contactName\":\"Productor Propio\"}";
        mvc.perform(put("/api/organizers/{organizerId}/contact", producerCanonical)
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfContactPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("productor.propio@duocuc.cl")))
                .andExpect(jsonPath("$.userOid", is(OID_PRODUCTOR)));

        // 2. Productor exclusivo intenta modificar contacto ajeno (alias u otro tid:oid) -> 403 Forbidden
        mvc.perform(put("/api/organizers/{organizerId}/contact", "org-ajeno-01")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfContactPayload))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/organizers/{organizerId}/contact", ENTRA_ORG_ID)
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfContactPayload))
                .andExpect(status().isForbidden());

        // 3. Productor exclusivo intenta alterar userOid hacia otro tercero -> 403 Forbidden
        String tamperedOid = "{\"email\":\"productor.propio@duocuc.cl\",\"userOid\":\"" + OID_UNAUTHORIZED + "\"}";
        mvc.perform(put("/api/organizers/{organizerId}/contact", producerCanonical)
                        .with(tokenFor(OID_PRODUCTOR, List.of("Productor")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedOid))
                .andExpect(status().isForbidden());

        // 4. Admin conserva permisos legítimos sobre contactos de terceros -> 200 OK
        mvc.perform(put("/api/organizers/{organizerId}/contact", "org-admin-managed")
                        .with(tokenFor(OID_PRODUCTOR, List.of("Admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"cliente@admin.cl\",\"contactName\":\"Gestionado Admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is("cliente@admin.cl")));

        // 5. Organizador exclusivo conserva autorización propia y bloqueo ajeno
        mvc.perform(put("/api/organizers/{organizerId}/contact", ENTRA_ORG_ID)
                        .with(tokenFor(OID_ENTRA_ORG, List.of("Organizador")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"org.propio@duocuc.cl\"}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/organizers/{organizerId}/contact", producerCanonical)
                        .with(tokenFor(OID_ENTRA_ORG, List.of("Organizador")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"intruso@duocuc.cl\"}"))
                .andExpect(status().isForbidden());
    }
}
