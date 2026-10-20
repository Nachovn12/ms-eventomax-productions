package cl.duoc.eventomax.productions.security;

import cl.duoc.eventomax.productions.dto.OutboxRecoveryResult;
import cl.duoc.eventomax.productions.dto.OutboxRecoveryStatus;
import cl.duoc.eventomax.productions.service.OutboxManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class OutboxAdminSecurityTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    OutboxManagementService outboxManagementService;

    @Test
    void retry_withoutToken_returns401Unauthorized() throws Exception {
        UUID outboxId = UUID.randomUUID();
        mockMvc.perform(post("/api/productions/admin/outbox/{id}/retry", outboxId)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void retry_withWrongRole_returns403Forbidden() throws Exception {
        UUID outboxId = UUID.randomUUID();
        mockMvc.perform(post("/api/productions/admin/outbox/{id}/retry", outboxId)
                .with(jwt().jwt(b -> b.claim("sub", "productor-123"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_access_as_user"),
                                     new SimpleGrantedAuthority("ROLE_Productor")))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void retry_withMissingScope_returns403Forbidden() throws Exception {
        UUID outboxId = UUID.randomUUID();
        // Has ROLE_Admin but lacks SCOPE_access_as_user
        mockMvc.perform(post("/api/productions/admin/outbox/{id}/retry", outboxId)
                .with(jwt().jwt(b -> b.claim("sub", "admin-123"))
                        .authorities(new SimpleGrantedAuthority("ROLE_Admin")))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void retry_withAuthorizedAdmin_returns200Ok() throws Exception {
        UUID outboxId = UUID.randomUUID();
        OutboxRecoveryResult expected = new OutboxRecoveryResult(
                outboxId,
                OutboxRecoveryStatus.RECOVERED,
                "Evento recuperado exitosamente",
                3,
                LocalDateTime.now()
        );
        when(outboxManagementService.retryFailedEvent(eq(outboxId), any())).thenReturn(expected);

        mockMvc.perform(post("/api/productions/admin/outbox/{id}/retry", outboxId)
                .with(jwt().jwt(b -> b.claim("sub", "admin-123"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_access_as_user"),
                                     new SimpleGrantedAuthority("ROLE_Admin")))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.outboxId").value(outboxId.toString()));
    }
}

