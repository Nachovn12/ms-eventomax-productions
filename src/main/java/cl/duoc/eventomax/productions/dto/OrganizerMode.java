package cl.duoc.eventomax.productions.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Modalidad de asignación de organizador al crear una producción (SELF: identidad autenticada; EXTERNAL: cliente tercero)")
public enum OrganizerMode {
    SELF,
    EXTERNAL
}
