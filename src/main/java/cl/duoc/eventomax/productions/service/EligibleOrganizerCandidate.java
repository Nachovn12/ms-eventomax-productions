package cl.duoc.eventomax.productions.service;

import cl.duoc.eventomax.productions.dto.EligibleOrganizerSource;

/**
 * Representación interna de un organizador elegible obtenida desde un {@link EligibleOrganizerProvider}.
 * No se serializa directamente al cliente HTTP para evitar filtrar datos del directorio.
 */
public record EligibleOrganizerCandidate(
        String organizerId,
        String displayName,
        EligibleOrganizerSource source,
        String userOid,
        String deliverableEmail
) {}
