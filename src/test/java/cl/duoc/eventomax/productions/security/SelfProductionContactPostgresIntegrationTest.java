package cl.duoc.eventomax.productions.security;

import cl.duoc.eventomax.productions.dto.OrganizerMode;
import cl.duoc.eventomax.productions.dto.ProductionRequestDTO;
import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.model.OrganizerContact;
import cl.duoc.eventomax.productions.repository.OrganizerContactRepository;
import cl.duoc.eventomax.productions.repository.OutboxEventRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import cl.duoc.eventomax.productions.service.ProductionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración reales contra PostgreSQL 16 (Testcontainers) para Etapa 1:
 * creación SELF automática, idempotencia ON CONFLICT DO NOTHING, concurrencia y rollback atómico.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SelfProductionContactPostgresIntegrationTest {

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
    static final String OID_ADMIN_PROD = "11111111-2222-4333-8444-555555555551";
    static final String OID_PROD_ONLY = "11111111-2222-4333-8444-555555555552";
    static final String OID_ORG_ONLY = "11111111-2222-4333-8444-555555555553";
    static final String OID_OTHER = "11111111-2222-4333-8444-555555555559";

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
    JwtDecoder jwtDecoder;

    @MockitoBean
    CatalogClient catalogClient;

    @BeforeEach
    void cleanDb() {
        reset(outboxEventRepository);
        outboxEventRepository.deleteAll();
        productionRepository.deleteAll();
        contactRepository.deleteAll();
    }

    private JwtRequestPostProcessor tokenFor(String oid, List<String> roles, String preferredUsername, String email, String name) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("SCOPE_access_as_user"));
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return jwt().authorities(authorities.toArray(SimpleGrantedAuthority[]::new))
                .jwt(j -> {
                    j.subject("sub-" + oid)
                            .issuer("https://login.microsoftonline.com/" + TID + "/v2.0")
                            .claim("tid", TID)
                            .claim("oid", oid)
                            .claim("roles", roles)
                            .claim("scp", "access_as_user");
                    if (preferredUsername != null) j.claim("preferred_username", preferredUsername);
                    if (email != null) j.claim("email", email);
                    if (name != null) j.claim("name", name);
                });
    }

    private String selfBody(String eventName) {
        return "{\"organizerMode\":\"SELF\",\"name\":\"" + eventName + "\","
                + "\"scheduledAt\":\"2035-06-15T18:00:00\",\"location\":\"Centro Parque\"}";
    }

    @Test
    void selfCreation_adminPlusProductor_withoutOrganizadorRole_provisionsContactAndOutboxAtomically() throws Exception {
        String expectedOwnerId = (TID + ":" + OID_ADMIN_PROD).toLowerCase(Locale.ROOT);

        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_ADMIN_PROD, List.of("Admin", "Productor"), "ig.valeria@duocuc.cl", null, "Ignacio Valeria"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfBody("Cumbre Admin+Productor")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizerId", is(expectedOwnerId)))
                .andExpect(jsonPath("$.status", is("SOLICITADO")));

        OrganizerContact savedContact = contactRepository.findByOrganizerIdAndActiveTrue(expectedOwnerId).orElseThrow();
        assertThat(savedContact.getEmail()).isEqualTo("ig.valeria@duocuc.cl");
        assertThat(savedContact.getContactName()).isEqualTo("Ignacio Valeria");
        assertThat(savedContact.getUserOid()).isEqualTo(OID_ADMIN_PROD);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    void selfCreation_productorOnly_and_organizadorOnly_succeedAndDeriveTidOid() throws Exception {
        String prodOwner = (TID + ":" + OID_PROD_ONLY).toLowerCase(Locale.ROOT);
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PROD_ONLY, List.of("Productor"), "productor@duocuc.cl", null, "Productor Uno"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfBody("Evento Productor Propio")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizerId", is(prodOwner)));

        assertThat(contactRepository.findByOrganizerIdAndActiveTrue(prodOwner))
                .isPresent()
                .get()
                .extracting(OrganizerContact::getEmail, OrganizerContact::getUserOid)
                .containsExactly("productor@duocuc.cl", OID_PROD_ONLY);

        String orgOwner = (TID + ":" + OID_ORG_ONLY).toLowerCase(Locale.ROOT);
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_ORG_ONLY, List.of("Organizador"), "organizador@duocuc.cl", null, "Organizador Uno"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfBody("Evento Organizador Propio")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizerId", is(orgOwner)));

        assertThat(contactRepository.findByOrganizerIdAndActiveTrue(orgOwner))
                .isPresent()
                .get()
                .extracting(OrganizerContact::getEmail, OrganizerContact::getUserOid)
                .containsExactly("organizador@duocuc.cl", OID_ORG_ONLY);
    }

    @Test
    void selfCreation_rejectedForAdminOnlyAndAuditor_andExternalRejectedForOrganizadorOnly() throws Exception {
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_OTHER, List.of("Admin"), "admin@duocuc.cl", null, "Solo Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfBody("No permitido Admin solo")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_OTHER, List.of("Auditor"), "auditor@duocuc.cl", null, "Solo Auditor"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfBody("No permitido Auditor")))
                .andExpect(status().isForbidden());

        String externalBody = "{\"organizerMode\":\"EXTERNAL\",\"organizerId\":\"org-externo\",\"name\":\"Externo\","
                + "\"scheduledAt\":\"2035-06-15T18:00:00\",\"location\":\"Santiago\"}";
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_ORG_ONLY, List.of("Organizador"), "organizador@duocuc.cl", null, "Org"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(externalBody))
                .andExpect(status().isForbidden());

        assertThat(productionRepository.count()).isZero();
        assertThat(contactRepository.count()).isZero();
    }

    @Test
    void selfCreation_rejectsContradictoryOrganizerId_andProtectsForeignCanonicalIdentity() throws Exception {
        String foreignCanonical = TID + ":" + OID_OTHER;
        String tamperedBody = "{\"organizerMode\":\"SELF\",\"organizerId\":\"" + foreignCanonical + "\","
                + "\"name\":\"Intento suplantacion\",\"scheduledAt\":\"2035-06-15T18:00:00\",\"location\":\"Santiago\"}";

        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_ADMIN_PROD, List.of("Admin", "Productor"), "ig.valeria@duocuc.cl", null, "Ignacio"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedBody))
                .andExpect(status().isBadRequest());

        assertThat(productionRepository.count()).isZero();
        assertThat(contactRepository.count()).isZero();
    }

    @Test
    void selfCreation_handlesMissingAndExtGuestEmailsWithoutFailingProduction() throws Exception {
        String guestOwner = (TID + ":" + OID_PROD_ONLY).toLowerCase(Locale.ROOT);
        // Guest #EXT# UPN without fallback email -> production created, contact NOT inserted
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_PROD_ONLY, List.of("Productor"), "user_gmail.com#EXT#@tenant.onmicrosoft.com", null, "Guest"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfBody("Evento Invitado EXT")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizerId", is(guestOwner)));

        assertThat(contactRepository.findByOrganizerId(guestOwner)).isEmpty();

        // Missing email completely -> production created, contact NOT inserted
        String noEmailOwner = (TID + ":" + OID_ORG_ONLY).toLowerCase(Locale.ROOT);
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_ORG_ONLY, List.of("Organizador"), null, null, "Sin Correo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfBody("Evento Sin Correo")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizerId", is(noEmailOwner)));

        assertThat(contactRepository.findByOrganizerId(noEmailOwner)).isEmpty();

        // Guest #EXT# in preferred_username WITH valid email claim -> uses fallback email claim
        String fallbackOwner = (TID + ":" + OID_ADMIN_PROD).toLowerCase(Locale.ROOT);
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_ADMIN_PROD, List.of("Admin", "Productor"),
                                "ig.valeria_duocuc.cl#EXT#@tenant.onmicrosoft.com", "ig.valeria@duocuc.cl", "Ignacio"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfBody("Evento Fallback Email")))
                .andExpect(status().isCreated());

        assertThat(contactRepository.findByOrganizerIdAndActiveTrue(fallbackOwner))
                .isPresent()
                .get()
                .extracting(OrganizerContact::getEmail)
                .isEqualTo("ig.valeria@duocuc.cl");
    }

    @Test
    void selfCreation_doesNotOverwriteExistingCustomContact() throws Exception {
        String ownerId = (TID + ":" + OID_ADMIN_PROD).toLowerCase(Locale.ROOT);
        contactRepository.saveAndFlush(new OrganizerContact(
                ownerId, "personalizado@empresa.cl", "Contacto Custom", "+56999999999", OID_ADMIN_PROD, ownerId));

        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_ADMIN_PROD, List.of("Admin", "Productor"), "otro-login@duocuc.cl", null, "Otro Nombre"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfBody("Segunda produccion propia")))
                .andExpect(status().isCreated());

        OrganizerContact persisted = contactRepository.findByOrganizerIdAndActiveTrue(ownerId).orElseThrow();
        assertThat(persisted.getEmail()).isEqualTo("personalizado@empresa.cl");
        assertThat(persisted.getContactName()).isEqualTo("Contacto Custom");
        assertThat(persisted.getPhone()).isEqualTo("+56999999999");
        assertThat(persisted.getUserOid()).isEqualTo(OID_ADMIN_PROD);
    }

    @Test
    void selfCreation_concurrentRequestsOnPostgres_allSucceedWithSingleContactRow() throws Exception {
        int threads = 6;
        String ownerId = (TID + ":" + OID_ADMIN_PROD).toLowerCase(Locale.ROOT);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                Callable<Integer> task = () -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    return mvc.perform(post("/api/productions")
                                    .with(tokenFor(OID_ADMIN_PROD, List.of("Admin", "Productor"), "ig.valeria@duocuc.cl", null, "Ignacio"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(selfBody("Concurrente " + idx)))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                };
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            for (Future<Integer> f : futures) {
                assertThat(f.get(15, TimeUnit.SECONDS)).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(productionRepository.count()).isEqualTo(threads);
        assertThat(outboxEventRepository.count()).isEqualTo(threads);
        assertThat(contactRepository.findAll()).hasSize(1);
        assertThat(contactRepository.findByOrganizerIdAndActiveTrue(ownerId))
                .isPresent()
                .get()
                .extracting(OrganizerContact::getEmail)
                .isEqualTo("ig.valeria@duocuc.cl");
    }

    @Test
    void selfCreation_rollsBackContactAndProductionWhenOutboxFails_zeroOrphanContacts() {
        String ownerId = (TID + ":" + OID_ADMIN_PROD).toLowerCase(Locale.ROOT);
        Jwt jwt = Jwt.withTokenValue("mock")
                .header("alg", "none")
                .subject("sub-" + OID_ADMIN_PROD)
                .issuer("https://login.microsoftonline.com/" + TID + "/v2.0")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("tid", TID)
                .claim("oid", OID_ADMIN_PROD)
                .claim("preferred_username", "ig.valeria@duocuc.cl")
                .claim("name", "Ignacio Valeria")
                .build();

        ProductionRequestDTO req = new ProductionRequestDTO(
                ownerId, "Rollback Test", LocalDateTime.now().plusDays(10), "Santiago", null, null, OrganizerMode.SELF);

        doThrow(new RuntimeException("Simulated Outbox DB failure")).when(outboxEventRepository).save(any());

        assertThatThrownBy(() -> productionService.createProduction(req, ownerId, jwt))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("outbox");

        assertThat(productionRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(contactRepository.count()).isZero();
    }

    @Test
    void legacyCreation_withoutOrganizerMode_remainsCompatibleAndValidatesOrganizerId() throws Exception {
        String legacyBody = "{\"organizerId\":\"org-legacy-client\",\"name\":\"Evento Legacy\","
                + "\"scheduledAt\":\"2035-06-15T18:00:00\",\"location\":\"Valparaiso\"}";

        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_ADMIN_PROD, List.of("Admin", "Productor"), "ig.valeria@duocuc.cl", null, "Ignacio"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacyBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizerId", is("org-legacy-client")));

        // Legacy without organizerMode and without organizerId -> 400 Bad Request
        String invalidLegacy = "{\"name\":\"Sin Organizador\",\"scheduledAt\":\"2035-06-15T18:00:00\",\"location\":\"Santiago\"}";
        mvc.perform(post("/api/productions")
                        .with(tokenFor(OID_ADMIN_PROD, List.of("Admin", "Productor"), "ig.valeria@duocuc.cl", null, "Ignacio"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidLegacy))
                .andExpect(status().isBadRequest());
    }
}
