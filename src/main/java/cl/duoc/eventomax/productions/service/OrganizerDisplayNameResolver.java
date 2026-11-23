package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.OrganizerResolutionSource;
import cl.duoc.eventomax.productions.integration.graph.EntraUserDisplayNameClient;
import cl.duoc.eventomax.productions.model.OrganizerContact;
import cl.duoc.eventomax.productions.repository.OrganizerContactRepository;
import cl.duoc.eventomax.productions.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resuelve el nombre visible real ({@code displayName}) del organizador de una o varias producciones
 * a partir de su {@code organizerId} almacenado en PostgreSQL, sin inventar nombres ni mezclar
 * identidades de la sesión que consulta.
 * <p>
 * Reglas:
 * 1. Identidad canónica Entra ({@code tenantId:objectId}): valida formato UUID:UUID y coincidencia
 *    estricta con el tenant configurado; consulta Microsoft Graph (con caché TTL y agrupación por lote)
 *    para cualquier cuenta habilitada (Admin, Productor, Organizador).
 * 2. Identificador de otro tenant: jamás consulta Graph y devuelve {@link OrganizerResolutionSource#UNRESOLVED}.
 * 3. Cliente externo o alias de negocio (no canónico): jamás consulta Graph; resuelve en lote desde
 *    {@code organizer_contacts.contact_name}.
 * 4. Si Graph falla o está deshabilitado: degrada a {@link OrganizerResolutionSource#UNRESOLVED}
 *    sin afectar la lectura HTTP 200 de producciones.
 */
@Service
public class OrganizerDisplayNameResolver {

    private static final Logger log = LoggerFactory.getLogger(OrganizerDisplayNameResolver.class);

    public record ResolvedOrganizer(
            String displayName,
            OrganizerResolutionSource source
    ) {
        public static final ResolvedOrganizer UNRESOLVED =
                new ResolvedOrganizer(null, OrganizerResolutionSource.UNRESOLVED);
    }

    private final EntraUserDisplayNameClient graphUserClient;
    private final OrganizerContactRepository organizerContactRepository;

    @Autowired
    public OrganizerDisplayNameResolver(
            @Autowired(required = false) EntraUserDisplayNameClient graphUserClient,
            @Autowired(required = false) OrganizerContactRepository organizerContactRepository) {
        this.graphUserClient = graphUserClient;
        this.organizerContactRepository = organizerContactRepository;
    }

    public ResolvedOrganizer resolveOne(String rawOrganizerId) {
        if (rawOrganizerId == null || rawOrganizerId.isBlank()) {
            return ResolvedOrganizer.UNRESOLVED;
        }
        String trimmed = rawOrganizerId.trim();
        return resolveAll(List.of(trimmed)).getOrDefault(trimmed, ResolvedOrganizer.UNRESOLVED);
    }

    public Map<String, ResolvedOrganizer> resolveAll(Collection<String> rawOrganizerIds) {
        Map<String, ResolvedOrganizer> resolved = new LinkedHashMap<>();
        if (rawOrganizerIds == null || rawOrganizerIds.isEmpty()) {
            return resolved;
        }

        Set<String> uniqueIds = new LinkedHashSet<>();
        for (String raw : rawOrganizerIds) {
            if (raw != null && !raw.isBlank()) {
                uniqueIds.add(raw.trim());
            }
        }
        if (uniqueIds.isEmpty()) {
            return resolved;
        }

        Map<String, String> canonicalIdToOid = new LinkedHashMap<>();
        Set<String> oidsToLookup = new LinkedHashSet<>();
        Set<String> nonCanonicalIds = new LinkedHashSet<>();

        boolean graphReady = graphUserClient != null && graphUserClient.isUserLookupReady();
        String configuredTenant = graphReady ? graphUserClient.getConfiguredTenantId() : "";

        for (String id : uniqueIds) {
            if (SecurityUtils.isCanonicalOwnerId(id)) {
                String[] parts = id.toLowerCase(Locale.ROOT).split(":");
                String tid = parts[0];
                String oid = parts[1];
                if (graphReady && !configuredTenant.isBlank() && configuredTenant.equalsIgnoreCase(tid)) {
                    canonicalIdToOid.put(id, oid);
                    oidsToLookup.add(oid);
                } else {
                    resolved.put(id, ResolvedOrganizer.UNRESOLVED);
                }
            } else {
                nonCanonicalIds.add(id);
            }
        }

        if (!oidsToLookup.isEmpty()) {
            try {
                Map<String, Optional<String>> graphNames = graphUserClient.lookupUserDisplayNames(oidsToLookup);
                for (Map.Entry<String, String> entry : canonicalIdToOid.entrySet()) {
                    Optional<String> nameOpt = graphNames.getOrDefault(entry.getValue(), Optional.empty());
                    if (nameOpt != null && nameOpt.isPresent() && !nameOpt.get().isBlank()) {
                        resolved.put(entry.getKey(),
                                new ResolvedOrganizer(nameOpt.get().trim(), OrganizerResolutionSource.ENTRA_GRAPH));
                    } else {
                        resolved.put(entry.getKey(), ResolvedOrganizer.UNRESOLVED);
                    }
                }
            } catch (Exception ex) {
                log.warn("[ORGANIZER_RESOLVE_GRAPH_ERROR] Degradando resolución de organizadores Entra ID: {}",
                        ex.getClass().getSimpleName());
                for (String id : canonicalIdToOid.keySet()) {
                    resolved.put(id, ResolvedOrganizer.UNRESOLVED);
                }
            }
        }

        if (!nonCanonicalIds.isEmpty()) {
            Map<String, String> externalNames = new LinkedHashMap<>();
            if (organizerContactRepository != null) {
                try {
                    List<OrganizerContact> contacts =
                            organizerContactRepository.findByOrganizerIdInAndActiveTrue(nonCanonicalIds);
                    if (contacts != null) {
                        for (OrganizerContact contact : contacts) {
                            if (contact != null
                                    && contact.getOrganizerId() != null
                                    && contact.getContactName() != null
                                    && !contact.getContactName().isBlank()) {
                                externalNames.put(contact.getOrganizerId().trim(), contact.getContactName().trim());
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.warn("[ORGANIZER_RESOLVE_DB_ERROR] No se pudo consultar nombres de clientes externos: {}",
                            ex.getClass().getSimpleName());
                }
            }

            for (String id : nonCanonicalIds) {
                String name = externalNames.get(id);
                if (name != null && !name.isBlank()) {
                    resolved.put(id, new ResolvedOrganizer(name, OrganizerResolutionSource.EXTERNAL_CONTACT));
                } else {
                    resolved.put(id, ResolvedOrganizer.UNRESOLVED);
                }
            }
        }

        return resolved;
    }
}
