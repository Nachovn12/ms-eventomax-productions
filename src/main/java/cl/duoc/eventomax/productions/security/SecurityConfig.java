package cl.duoc.eventomax.productions.security;

import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new EntraJwtAuthoritiesConverter());
        return http.csrf(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/productions", "/api/productions/{id}", "/api/productions/{id}/services")
                    .access(scopeAndAnyRole("Admin", "Productor", "Organizador"))
                .requestMatchers(HttpMethod.POST, "/api/productions")
                    .access(scopeAndAnyRole("Productor", "Organizador"))
                .requestMatchers(HttpMethod.PUT, "/api/productions/{id}/status")
                    .access(scopeAndAnyRole("Admin", "Productor"))
                .requestMatchers(HttpMethod.POST, "/api/productions/{id}/quote-command/republish")
                    .access(scopeAndAnyRole("Admin", "Productor"))
                .requestMatchers(HttpMethod.POST, "/api/productions/admin/outbox/**")
                    .access(scopeAndAnyRole("Admin"))
                .requestMatchers(HttpMethod.GET, "/api/organizers/{organizerId}/contact")
                    .access(scopeAndAnyRole("Admin", "Productor", "Organizador"))
                .requestMatchers(HttpMethod.PUT, "/api/organizers/{organizerId}/contact")
                    .access(scopeAndAnyRole("Admin", "Organizador"))
                .requestMatchers(HttpMethod.GET, "/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html")
                    .access(scopeAndAnyRole("Admin"))
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
            .build();
    }

    private static AuthorizationManager<RequestAuthorizationContext> scopeAndAnyRole(String... roles) {
        return AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAuthority("SCOPE_access_as_user"),
            AuthorityAuthorizationManager.hasAnyRole(roles));
    }

    public static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        Assert.hasText(issuer, "JWT issuer is required");
        Assert.hasText(audience, "JWT audience is required");
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
            new JwtAudienceValidator(audience), new JwtClaimValidator<Instant>("exp", Objects::nonNull),
            new JwtClaimValidator<String>("sub", s -> s != null && !s.isBlank()));
    }

    @Bean
    public JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                                 @Value("${eventomax.security.jwt.audience}") String audience) {
        var validator = tokenValidator(issuer, audience);
        return new SupplierJwtDecoder(() -> {
            var decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
            decoder.setJwtValidator(validator);
            return decoder;
        });
    }
}
