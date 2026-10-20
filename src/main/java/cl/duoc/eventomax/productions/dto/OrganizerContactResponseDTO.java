package cl.duoc.eventomax.productions.dto;

import cl.duoc.eventomax.productions.model.OrganizerContact;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "Detalle del contacto autorizado de un organizador")
public record OrganizerContactResponseDTO(

        @Schema(description = "ID del registro", example = "1")
        Long id,

        @Schema(description = "Identificador de negocio del organizador", example = "org-ep2-demo-001")
        String organizerId,

        @Schema(description = "Correo electrónico autorizado", example = "contacto@organizador.cl")
        String email,

        @Schema(description = "Nombre de contacto", example = "Ignacio Producciones")
        String contactName,

        @Schema(description = "Teléfono de contacto", example = "+56912345678")
        String phone,

        @Schema(description = "Object ID de Microsoft Entra ID vinculado", example = "d762a6e9-9134-4a4b-97e3-057bfd9d883b")
        String userOid,

        @Schema(description = "Indica si el contacto se encuentra activo", example = "true")
        boolean active,

        @Schema(description = "Fecha de registro", example = "2026-10-19T14:30:00")
        LocalDateTime createdAt,

        @Schema(description = "Fecha de última actualización", example = "2026-10-19T14:30:00")
        LocalDateTime updatedAt,

        @Schema(description = "Actor que creó el registro", example = "user-entra-id-sub")
        String createdBy,

        @Schema(description = "Actor que actualizó el registro", example = "user-entra-id-sub")
        String updatedBy
) {
    public static OrganizerContactResponseDTO fromEntity(OrganizerContact entity) {
        return new OrganizerContactResponseDTO(
                entity.getId(),
                entity.getOrganizerId(),
                entity.getEmail(),
                entity.getContactName(),
                entity.getPhone(),
                entity.getUserOid(),
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getCreatedBy(),
                entity.getUpdatedBy()
        );
    }
}

