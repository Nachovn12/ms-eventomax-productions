package cl.duoc.eventomax.productions.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Solicitud para registrar o actualizar el contacto de un organizador")
public record OrganizerContactRequestDTO(

        @Schema(description = "Correo electrónico real y autorizado", example = "contacto@organizador.cl")
        @NotBlank(message = "El correo electrónico es obligatorio")
        @Email(message = "El formato del correo electrónico no es válido")
        @Size(max = 255, message = "El correo no debe exceder 255 caracteres")
        String email,

        @Schema(description = "Nombre de la persona o entidad de contacto", example = "Ignacio Producciones")
        @Size(max = 150, message = "El nombre de contacto no debe exceder 150 caracteres")
        String contactName,

        @Schema(description = "Teléfono de contacto", example = "+56912345678")
        @Size(max = 50, message = "El teléfono no debe exceder 50 caracteres")
        String phone,

        @Schema(description = "Object ID de Microsoft Entra ID del organizador (opcional, asignable por administrador)", example = "d762a6e9-9134-4a4b-97e3-057bfd9d883b")
        @Size(max = 255, message = "El userOid no debe exceder 255 caracteres")
        String userOid
) {
    public OrganizerContactRequestDTO(String email, String contactName, String phone) {
        this(email, contactName, phone, null);
    }
}

