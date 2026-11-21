package cl.duoc.eventomax.productions.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Elemento mínimo de organizador elegible para el selector de nueva producción")
public record EligibleOrganizerDTO(
        @Schema(description = "Identificador interno del organizador (tid:oid para Entra o ext-<uuid>/alias para externo)")
        String organizerId,

        @Schema(description = "Nombre visible del organizador o cliente")
        String displayName,

        @Schema(description = "Origen del organizador (ENTRA_ORGANIZER o EXTERNAL_CONTACT)")
        EligibleOrganizerSource source,

        @Schema(description = "Indica si existe un contacto de notificaciones registrado (no implica verificación de buzón)")
        boolean hasRegisteredContact,

        @Schema(description = "Correo enmascarado del contacto registrado, cuando existe")
        String maskedEmail
) {}
