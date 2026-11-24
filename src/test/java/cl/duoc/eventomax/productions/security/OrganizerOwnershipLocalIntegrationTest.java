package cl.duoc.eventomax.productions.security;

import cl.duoc.eventomax.productions.integration.catalog.CatalogClient;
import cl.duoc.eventomax.productions.model.OrganizerContact;
import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.repository.OrganizerContactRepository;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Simulated JWT authentication + real H2 persistence. Never uses environment DB_URL. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:ownership-local;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.rabbitmq.listener.simple.auto-startup=false",
    "spring.rabbitmq.listener.direct.auto-startup=false", "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrganizerOwnershipLocalIntegrationTest {
    static final String TID = "33333333-3333-4333-8333-333333333333";
    static final String A = "11111111-1111-4111-8111-111111111111";
    static final String B = "22222222-2222-4222-8222-222222222222";
    @Autowired MockMvc mvc;
    @Autowired ProductionRepository productions;
    @Autowired OrganizerContactRepository contacts;
    @Autowired cl.duoc.eventomax.productions.repository.OutboxEventRepository outbox;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean CatalogClient catalog;

    private JwtRequestPostProcessor token(String oid, String role) {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_access_as_user"),
            new SimpleGrantedAuthority("ROLE_" + role)).jwt(j -> j.subject("subject-" + oid)
                .claim("tid", TID).claim("oid", oid).claim("roles", List.of(role))
                .claim("scp", "access_as_user").claim("preferred_username",
                    oid.equals(B) ? "changed@example.test" : "shared@example.test"));
    }

    private Production production(String organizer, String oid) {
        contacts.save(new OrganizerContact(organizer, "shared@example.test", "Test", null, oid, "test-admin"));
        return productions.saveAndFlush(new Production(organizer, organizer, LocalDateTime.now().plusDays(2), "Test venue"));
    }

    @Test void bothOrganizersSeeOnlyTheirBusinessAliasesAndCannotReadEachOther() throws Exception {
        Production pa = production("business-a", A);
        Production pb = production("business-b", B);
        for (String oid : List.of(A, B)) {
            String own = oid.equals(A) ? "business-a" : "business-b";
            long foreignId = oid.equals(A) ? pb.getId() : pa.getId();
            mvc.perform(get("/api/productions").with(token(oid, "Organizador")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].organizerId", is(own)));
            mvc.perform(get("/api/productions/{id}", foreignId).with(token(oid, "Organizador")))
                .andExpect(status().isForbidden());
            mvc.perform(get("/api/productions/{id}/services", foreignId).with(token(oid, "Organizador")))
                .andExpect(status().isForbidden());
        }
    }

    @Test void changingContactEmailDoesNotGrantOwnership() throws Exception {
        Production p = production("business-a", A);
        mvc.perform(get("/api/productions/{id}", p.getId()).with(token(B, "Organizador")))
            .andExpect(status().isForbidden());
        // This is B's authenticated username: copying it into A's editable contact must not grant B access.
        mvc.perform(put("/api/organizers/business-a/contact").with(token(A, "Organizador"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"changed@example.test\"}"))
            .andExpect(status().isOk());
        assertEquals(A, contacts.findByOrganizerId("business-a").orElseThrow().getUserOid());
        mvc.perform(get("/api/productions/{id}", p.getId()).with(token(B, "Organizador")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/productions").with(token(B, "Organizador")))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test void organizerCannotReassignOidAndRejectedRequestDoesNotChangeContact() throws Exception {
        production("business-a", A);
        mvc.perform(put("/api/organizers/business-a/contact").with(token(A, "Organizador"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"changed@example.test\",\"userOid\":\"" + B + "\"}"))
            .andExpect(status().isForbidden());
        var contact = contacts.findByOrganizerId("business-a").orElseThrow();
        assertEquals(A, contact.getUserOid());
        assertEquals("shared@example.test", contact.getEmail());
    }

    @Test void canonicalOwnerCannotBeOverriddenByContradictoryContact() throws Exception {
        Production p = production(TID + ":" + A, B);
        mvc.perform(get("/api/productions/{id}", p.getId()).with(token(B, "Organizador")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/productions").with(token(B, "Organizador")))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test void historicalNineteenCharacterLinkIsPreservedButDoesNotGrantAccess() throws Exception {
        String historical = "legacy-owner-000001";
        assertEquals(19, historical.length());
        Production p = production("historical-business", historical);
        mvc.perform(get("/api/productions/{id}", p.getId()).with(token(A, "Organizador")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/productions").with(token(historical, "Organizador")))
            .andExpect(status().isForbidden());
        assertEquals(historical, contacts.findByOrganizerId("historical-business").orElseThrow().getUserOid());
        assertTrue(productions.findById(p.getId()).isPresent());
    }

    @Test void emptyOwnerCollectionCannotBecomeGlobalQuery() {
        production("business-a", A);
        assertTrue(productions.findByFilters(null, null, null, List.of()).isEmpty());
    }

    @Test void organizerCannotCreateForAnotherOwnerOrChangeEvenOwnStatus() throws Exception {
        Production p = production("business-a", A);
        mvc.perform(put("/api/productions/{id}/status", p.getId()).with(token(A, "Organizador"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELADO\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/productions").with(token(B, "Organizador"))
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"organizerId":"business-a","name":"Spoof","scheduledAt":"2035-01-01T12:00:00","location":"Test"}
                """))
            .andExpect(status().isForbidden());
    }

    @Test void validBusinessAliasCreationPreservesIdentifier() throws Exception {
        production("business-a", A);
        mvc.perform(post("/api/productions").with(token(A, "Organizador"))
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"organizerId":"business-a","name":"Own","scheduledAt":"2035-01-01T12:00:00","location":"Test"}
                """))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.organizerId", is("business-a")));
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void adminAndProductorKeepGlobalReadsAndStatusOperations() throws Exception {
        // Coordinator uses REQUIRES_NEW, so fixture rows must be committed in the isolated H2 DB.
        try {
            for (String role : List.of("Admin", "Productor")) {
                Production p = production("business-" + role, A);
                mvc.perform(get("/api/productions/{id}", p.getId()).with(token(B, role)))
                    .andExpect(status().isOk());
                mvc.perform(put("/api/productions/{id}/status", p.getId()).with(token(B, role))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELADO\"}"))
                    .andExpect(status().isOk());
            }
        } finally {
            outbox.deleteAll();
            productions.deleteAll();
            contacts.deleteAll();
        }
    }

    @Test void missingTokenAndAuditorRemainBlocked() throws Exception {
        mvc.perform(get("/api/productions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/productions").with(token(A, "Auditor"))).andExpect(status().isForbidden());
    }
}
