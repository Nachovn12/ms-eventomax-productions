package cl.duoc.eventomax.productions.integration.graph;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Puerto de solo lectura para resolver {@code displayName} reales de usuarios de Microsoft Entra ID
 * a partir de sus {@code objectId} (UUID) dentro del tenant configurado.
 */
public interface EntraUserDisplayNameClient {

    /**
     * Indica si el cliente está habilitado y posee la configuración mínima (tenant, clientId, clientSecret).
     */
    boolean isUserLookupReady();

    /**
     * Retorna el tenantId configurado (en minúsculas) para validar coincidencia estricta de tenant.
     */
    String getConfiguredTenantId();

    /**
     * Resuelve en lote (con caché TTL y deduplicación) el {@code displayName} real de cada {@code objectId}
     * perteneciente al tenant configurado. Nunca lanza excepciones no controladas ni inventa nombres.
     */
    Map<String, Optional<String>> lookupUserDisplayNames(Set<String> normalizedObjectIds);
}
