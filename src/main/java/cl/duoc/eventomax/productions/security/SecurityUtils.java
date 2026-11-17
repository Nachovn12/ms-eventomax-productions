package cl.duoc.eventomax.productions.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;

public final class SecurityUtils {

    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    /** Ownership is resolved only after the resource server validates the JWT.
     * Legacy sub/OID strings remain usable as audit actors, not ownership proofs.
     * Business aliases are scoped to the single tenant of the configured issuer.
     */
    public static String requireOrganizerOid(Jwt jwt) {
        if (jwt == null || !(jwt.getClaims().get("oid") instanceof String oid)
                || !(jwt.getClaims().get("tid") instanceof String tid)
                || !oid.matches(UUID_PATTERN) || !tid.matches(UUID_PATTERN)) {
            throw new AccessDeniedException("Se requiere identidad estable de organizador (tid y oid)");
        }
        // Entra's issuer contains its tenant; reject inconsistent signed claims too.
        if (jwt.getIssuer() != null && jwt.getIssuer().getHost().equalsIgnoreCase("login.microsoftonline.com")
                && !jwt.getIssuer().getPath().equalsIgnoreCase("/" + tid + "/v2.0")) {
            throw new AccessDeniedException("Tenant de organizador incompatible con el emisor");
        }
        return oid.toLowerCase(java.util.Locale.ROOT);
    }

    public static String requireOrganizerOwnerId(Jwt jwt) {
        String oid = requireOrganizerOid(jwt);
        return jwt.getClaimAsString("tid").toLowerCase(java.util.Locale.ROOT) + ":" + oid;
    }

    public static boolean isCanonicalOwnerId(String value) {
        return value != null && value.matches(UUID_PATTERN + ":" + UUID_PATTERN);
    }

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

    /**
     * Resuelve el correo o nombre de usuario principal a partir de los claims habituales de Microsoft Entra ID
     * (preferred_username, email, upn).
     */
    public static String resolveEmail(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            return preferredUsername.trim();
        }
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isBlank()) {
            return email.trim();
        }
        String upn = jwt.getClaimAsString("upn");
        if (upn != null && !upn.isBlank()) {
            return upn.trim();
        }
        return null;
    }

    /**
     * Extrae el Object ID puro (oid) o sub del usuario desde el JWT.
     */
    public static String resolveOid(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        String oid = jwt.getClaimAsString("oid");
        if (oid != null && !oid.isBlank()) {
            return oid.trim();
        }
        String sub = jwt.getSubject();
        if (sub != null && !sub.isBlank()) {
            return sub.trim();
        }
        return null;
    }
}

