package cl.duoc.eventomax.productions.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Origen de un organizador elegible en el selector de EventoMax")
public enum EligibleOrganizerSource {
    ENTRA_ORGANIZER,
    EXTERNAL_CONTACT
}
