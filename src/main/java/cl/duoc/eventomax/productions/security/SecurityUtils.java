package cl.duoc.eventomax.productions.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * Resuelve de forma estricta la identidad del propietario a partir del JWT autenticado.
     * Prioridad de identidad estable:
     * 1. Si existe claim 'oid' (Object ID) y 'tid' (Tenant ID), devuelve "tid:oid".
     * 2. Si existe claim 'oid' sin 'tid', devuelve 'oid'.
     * 3. Si no existe 'oid', recurre al claim 'sub' (Subject estándar OAuth2).
     * 4. Si la identidad resultante es nula o vacía, aplica fail-closed lanzando AccessDeniedException.
     */
    public static String resolveOwnerId(Jwt jwt) {
        if (jwt == null) {
            throw new AccessDeniedException("Token de autenticacion requerido");
        }

        String oid = jwt.getClaimAsString("oid");
        if (oid != null && !oid.isBlank()) {
            String tid = jwt.getClaimAsString("tid");
            if (tid != null && !tid.isBlank()) {
                return tid.trim() + ":" + oid.trim();
            }
            return oid.trim();
        }

        String sub = jwt.getSubject();
        if (sub != null && !sub.isBlank()) {
            return sub.trim();
        }

        throw new AccessDeniedException("No se pudo determinar una identidad de propietario valida en el token");
    }

    /**
     * Determina si el usuario autenticado posee exclusivamente el rol de Organizador
     * (es decir, no posee privilegios administrativos de Admin ni Productor).
     */
    public static boolean isOrganizerOnly(Authentication authentication, Jwt jwt) {
        if (authentication != null && authentication.getAuthorities() != null) {
            boolean hasPrivileged = authentication.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_Admin".equals(a.getAuthority()) || "ROLE_Productor".equals(a.getAuthority()));
            boolean hasOrganizer = authentication.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_Organizador".equals(a.getAuthority()));
            if (hasOrganizer && !hasPrivileged) {
                return true;
            }
            if (hasPrivileged) {
                return false;
            }
        }

        if (jwt != null && jwt.hasClaim("roles")) {
            Object rolesObj = jwt.getClaims().get("roles");
            if (rolesObj instanceof Collection<?> roles) {
                boolean hasPrivileged = roles.stream().anyMatch(r -> "Admin".equals(r) || "Productor".equals(r));
                boolean hasOrganizer = roles.stream().anyMatch("Organizador"::equals);
                return hasOrganizer && !hasPrivileged;
            }
        }

        return false;
    }
}

