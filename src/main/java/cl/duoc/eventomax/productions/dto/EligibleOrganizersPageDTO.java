package cl.duoc.eventomax.productions.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Respuesta paginada de organizadores elegibles para el selector de EventoMax")
public record EligibleOrganizersPageDTO(
        List<EligibleOrganizerDTO> items,
        int page,
        int size,
        long total,
        DirectorySyncStatus directorySyncStatus
) {}
