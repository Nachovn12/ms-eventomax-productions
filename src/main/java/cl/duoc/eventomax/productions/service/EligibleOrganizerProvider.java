package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.EligibleOrganizerSource;
import java.util.Optional;

/**
 * Contrato extensible para consultar y verificar organizadores elegibles de EventoMax.
 */
public interface EligibleOrganizerProvider {

    EligibleOrganizerSource source();

    /**
     * Obtiene los organizadores elegibles de esta fuente para el selector (puede usar caché de corta duración).
     */
    DirectoryFetchResult fetchEligibleOrganizers();

    /**
     * Verifica de forma autoritativa y fail-closed si un {@code organizerId} es elegible actualmente.
     * En fuentes externas (Microsoft Graph), ante fallo o desactivación lanza {@link ExternalDirectoryUnavailableException}
     * en lugar de confiar en caché antigua.
     */
    Optional<EligibleOrganizerCandidate> verifyEligibleOrganizer(String organizerId);
}
