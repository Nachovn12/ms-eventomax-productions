package cl.duoc.eventomax.productions.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Submodalidad de cliente cuando organizerMode = EXTERNAL")
public enum ExternalOrganizerType {
    ENTRA_ORGANIZER,
    EXISTING_EXTERNAL,
    NEW_EXTERNAL
}
