package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.DirectorySyncStatus;
import java.util.List;

/**
 * Resultado de consulta de un {@link EligibleOrganizerProvider}.
 */
public record DirectoryFetchResult(
        List<EligibleOrganizerCandidate> candidates,
        DirectorySyncStatus status,
        boolean authoritative
) {}
