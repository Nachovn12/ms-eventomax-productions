package cl.duoc.eventomax.productions.security;

import cl.duoc.eventomax.productions.controller.ProductionController;
import cl.duoc.eventomax.productions.dto.ProductionResponseDTO;
import cl.duoc.eventomax.productions.service.ProductionService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductionController.class)
@Import({SecurityConfig.class, ProductionJwtHttpTest.DecoderConfiguration.class})
@ActiveProfiles("test")
class ProductionJwtHttpTest {
    private static final String ISSUER = "https://issuer.invalid/test";
    private static final String AUDIENCE = "productions-test";
    private static final KeyPair KEYS = keys();
    @Autowired MockMvc mvc;
    @MockitoBean ProductionService service;
    @MockitoBean cl.duoc.eventomax.productions.service.OrganizerContactService organizerContactService;

    static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @TestConfiguration
    static class DecoderConfiguration {
        @Bean @Primary JwtDecoder signedTestDecoder() {
            var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            decoder.setJwtValidator(SecurityConfig.tokenValidator(ISSUER, AUDIENCE));
            return decoder;
        }
    }
    @BeforeEach void setup() {
        var now = LocalDateTime.now();
        var response = new ProductionResponseDTO(1L, "authenticated-actor", "Test", now.plusDays(2), "Santiago", "SOLICITADO", now, now);
        when(service.createProduction(any(), anyString())).thenReturn(response);
        when(service.updateStatus(anyLong(), any(), anyString())).thenReturn(response);
        when(service.getProductionById(1L)).thenReturn(Optional.of(response));
        when(organizerContactService.isAuthorizedForOrganizer(any(), any(), any())).thenReturn(true);
        when(organizerContactService.resolveAuthorizedOrganizerIds(any())).thenReturn(List.of("authenticated-actor"));
    }
    String token(String role, Consumer<JWTClaimsSet.Builder> changes) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject("authenticated-actor")
            .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("scp", "access_as_user")
            .claim("roles", List.of(role));
        changes.accept(claims);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(KEYS.getPrivate()));
        return jwt.serialize();
    }
    MockHttpServletRequestBuilder request(String method) {
        return switch (method) {
            case "POST" -> post("/api/productions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"organizerId\":\"authenticated-actor\",\"name\":\"Test\",\"scheduledAt\":\"2099-01-01T12:00:00\",\"location\":\"Santiago\"}");
            case "PUT" -> put("/api/productions/1/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELADO\"}");
            default -> get("/api/productions/1");
        };
    }
    static Stream<Arguments> roleCases() {
        return Stream.of("Admin", "Productor", "Organizador", "Auditor").flatMap(role -> Stream.of("GET", "POST", "PUT").map(method -> {
            boolean allowed = switch (method) {
                case "POST" -> role.equals("Productor") || role.equals("Organizador");
                case "PUT" -> role.equals("Admin") || role.equals("Productor");
                default -> !role.equals("Auditor");
            };
            return Arguments.of(role, method, allowed ? (method.equals("POST") ? 201 : 200) : 403);
        }));
    }
    @ParameterizedTest @MethodSource("roleCases")
    void signedTokenEnforcesBffRoleMatrix(String role, String method, int status) throws Exception {
        mvc.perform(request(method).header("Authorization", "Bearer " + token(role, b -> {})))
            .andExpect(status().is(status));
    }
    @Test void missingTokenIs401ForEveryOperation() throws Exception {
        for (String method : List.of("GET", "POST", "PUT")) mvc.perform(request(method)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void invalidTokensAre401() throws Exception {
        List<Consumer<JWTClaimsSet.Builder>> invalid = List.of(
            b -> b.issuer("https://wrong.invalid"), b -> b.audience("wrong"),
            b -> b.expirationTime(Date.from(Instant.now().minusSeconds(300))),
            b -> b.expirationTime(null), b -> b.subject(" "));
        for (var change : invalid) mvc.perform(request("POST").header("Authorization", "Bearer " + token("Productor", change)))
            .andExpect(status().isUnauthorized());
        String valid = token("Productor", b -> {});
        var tampered = SignedJWT.parse(valid);
        tampered = new SignedJWT(tampered.getHeader(), tampered.getJWTClaimsSet());
        tampered.sign(new RSASSASigner(keys().getPrivate()));
        mvc.perform(request("POST").header("Authorization", "Bearer " + tampered.serialize())).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void missingScopeAndMalformedAuthoritiesAre403() throws Exception {
        List<Consumer<JWTClaimsSet.Builder>> invalid = List.of(
            b -> b.claim("scp", null), b -> b.claim("scp", "other"),
            b -> b.claim("roles", List.of("Productor", 7)), b -> b.claim("roles", "Productor"),
            b -> b.claim("roles", List.of("Unknown")), b -> b.claim("scp", List.of(7)));
        for (var change : invalid) mvc.perform(request("POST").header("Authorization", "Bearer " + token("Productor", change)))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void authenticatedActorAndTraceHeadersArePreserved() throws Exception {
        mvc.perform(request("POST").header("Authorization", "Bearer " + token("Productor", b -> {}))
                .header("X-Actor", "spoofed").header("X-Trace-Id", "trace-92").header("X-Correlation-Id", "correlation-92"))
            .andExpect(status().isCreated()).andExpect(header().string("X-Trace-Id", "trace-92"))
            .andExpect(header().string("X-Correlation-Id", "correlation-92"));
        verify(service).createProduction(any(), eq("authenticated-actor"));
        assertThat(org.slf4j.MDC.get("traceId")).isNull();
        var response = mvc.perform(request("GET").header("Authorization", "Bearer " + token("Productor", b -> {}))
            .header("X-Trace-Id", "bad value")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("X-Trace-Id")).matches("[a-f0-9-]{36}");
        assertThat(response.getHeader("X-Correlation-Id")).matches("[a-f0-9-]{36}");
    }
}
