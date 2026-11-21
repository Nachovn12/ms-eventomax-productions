package cl.duoc.eventomax.productions.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Estado de sincronización con el directorio externo Microsoft Graph")
public enum DirectorySyncStatus {
    OK,
    DISABLED,
    DEGRADED
}
