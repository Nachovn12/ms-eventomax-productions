package cl.duoc.eventomax.productions.security;

import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.model.OrganizerContact;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.repository.OrganizerContactRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.MediaType;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Permanent PostgreSQL 16 regression; JWT authentication is simulated, never Entra real. */
@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false",
    "spring.rabbitmq.listener.direct.auto-startup=false", "spring.kafka.listener.auto-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProductionCanonicalOwnershipIntegrationTest {
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
    static { DB.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.datasource.driver-class-name", DB::getDriverClassName);
    }
    static final String TID = "abcdefab-3333-4333-8333-333333333333";
    static final String A = "abcdefab-1111-4111-8111-111111111111";
    static final String B = "abcdefab-2222-4222-8222-222222222222";
    @Autowired MockMvc mvc;
    @Autowired ProductionRepository productions;
    @Autowired OrganizerContactRepository contacts;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean CatalogClient catalog;

    private JwtRequestPostProcessor organizer(String oid) {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_access_as_user"),
            new SimpleGrantedAuthority("ROLE_Organizador")).jwt(j -> j.subject("local-subject")
                .claim("oid", oid).claim("tid", TID).claim("roles", List.of("Organizador"))
                .claim("scp", "access_as_user"));
    }
    private String request(String owner) {
        return "{\"organizerId\":\"" + owner + "\",\"name\":\"Canonical regression\","
            + "\"scheduledAt\":\"2035-01-01T12:00:00\",\"location\":\"Local test\"}";
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void createdCanonicalOwnerIsNormalizedAndVisibleInDetailAndList(boolean uppercase) throws Exception {
        String canonical = TID + ":" + A;
        String input = uppercase ? canonical.toUpperCase(Locale.ROOT) : canonical;
        String created = mvc.perform(post("/api/productions").with(organizer(A))
            .contentType(MediaType.APPLICATION_JSON).content(request(input)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.organizerId", is(canonical)))
            .andReturn().getResponse().getContentAsString();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).get("id").asLong();
        mvc.perform(get("/api/productions/{id}", id).with(organizer(A))).andExpect(status().isOk());
        mvc.perform(get("/api/productions").with(organizer(A))).andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id", is((int) id)));
        mvc.perform(get("/api/productions/{id}", id).with(organizer(B))).andExpect(status().isForbidden());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void storedCanonicalCaseDoesNotChangeOwnershipOrRequireDataMigration(boolean uppercase) throws Exception {
        String canonical = TID + ":" + A;
        String stored = uppercase ? canonical.toUpperCase(Locale.ROOT) : canonical;
        Production p = productions.saveAndFlush(new Production(stored, "Existing canonical", LocalDateTime.now().plusDays(2), "Test"));
        mvc.perform(get("/api/productions").param("status", "SOLICITADO").with(organizer(A.toUpperCase(Locale.ROOT))))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].organizerId", is(stored)));
        mvc.perform(get("/api/productions/{id}", p.getId()).with(organizer(A))).andExpect(status().isOk());
        mvc.perform(get("/api/productions").with(organizer(B.toUpperCase(Locale.ROOT))))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/productions/{id}", p.getId()).with(organizer(B))).andExpect(status().isForbidden());
        mvc.perform(post("/api/productions").with(organizer(B)).contentType(MediaType.APPLICATION_JSON)
            .content(request(stored))).andExpect(status().isForbidden());
    }

    @Test void businessAliasesDifferingOnlyInCaseRemainDistinctAndUnchanged() throws Exception {
        contacts.save(new OrganizerContact("Business-A", "same@example.test", null, null, A, "test-admin"));
        contacts.save(new OrganizerContact("business-a", "same@example.test", null, null, B, "test-admin"));
        var pa = productions.saveAndFlush(new Production("Business-A", "Own business", LocalDateTime.now().plusDays(2), "Test"));
        var pb = productions.saveAndFlush(new Production("business-a", "Foreign business", LocalDateTime.now().plusDays(2), "Test"));
        mvc.perform(get("/api/productions").with(organizer(A))).andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id", is(pa.getId().intValue())));
        mvc.perform(get("/api/productions/{id}", pb.getId()).with(organizer(A))).andExpect(status().isForbidden());
        mvc.perform(post("/api/productions").with(organizer(A)).contentType(MediaType.APPLICATION_JSON)
            .content(request("business-a"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/productions").with(organizer(A)).contentType(MediaType.APPLICATION_JSON)
            .content(request("Business-A"))).andExpect(status().isCreated())
            .andExpect(jsonPath("$.organizerId", is("Business-A")));
    }
}
