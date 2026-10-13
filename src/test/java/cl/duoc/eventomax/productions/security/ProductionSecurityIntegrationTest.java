package cl.duoc.eventomax.productions.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ProductionSecurityIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void createProduction_WithoutAuth_Returns401() throws Exception {
        mockMvc.perform(post("/api/productions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createProduction_WithAuth_ButNoSubject_Returns403() throws Exception {
        // Jwt with no subject
        Jwt token = Jwt.withTokenValue("mock")
                .header("alg", "none")
                .claim("iss", "dummy")
                // no sub claim
                .build();

        String validBody = """
                {
                  "name": "Test",
                  "organizerId": "org",
                  "scheduledAt": "2030-01-01T12:00:00",
                  "location": "Loc"
                }
                """;
                
        mockMvc.perform(post("/api/productions")
                .with(jwt().jwt(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody))
                .andExpect(status().isForbidden()); // Because AccessDeniedException translates to 403
    }
}
