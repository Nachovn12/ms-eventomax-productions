package cl.duoc.eventomax.productions.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

public final class EntraJwtAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

	private static final Set<String> ROLES = Set.of("Admin", "Productor", "Organizador", "Auditor");
	private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

	@Override
	public Collection<GrantedAuthority> convert(Jwt jwt) {
		var authorities = new LinkedHashSet<GrantedAuthority>();
		// Preserve Spring's scope/scp precedence, but never cast malformed collection entries.
		Object scope = jwt.getClaims().get(jwt.hasClaim("scope") ? "scope" : "scp");
		if (scope instanceof String || scope instanceof Collection<?> values
				&& values.stream().allMatch(String.class::isInstance)) {
			authorities.addAll(this.scopes.convert(jwt));
		}

		// Entra roles are an array of strings. A malformed array grants no role authorities.
		Object roles = jwt.getClaims().get("roles");
		if (roles instanceof Collection<?> values && values.stream().allMatch(String.class::isInstance)) {
			values.stream().map(String.class::cast).filter(ROLES::contains)
					.map(role -> new SimpleGrantedAuthority("ROLE_" + role)).forEach(authorities::add);
		}
		return authorities;
	}

}
