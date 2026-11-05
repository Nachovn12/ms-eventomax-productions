package cl.duoc.eventomax.productions.controller;

import cl.duoc.eventomax.productions.dto.OrganizerContactRequestDTO;
import cl.duoc.eventomax.productions.dto.OrganizerContactResponseDTO;
import cl.duoc.eventomax.productions.service.OrganizerContactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/organizers")
@Tag(name = "Organizer Contacts", description = "API para la administración de contactos autorizados de organizadores")
public class OrganizerContactController {

    private final OrganizerContactService service;

    public OrganizerContactController(OrganizerContactService service) {
        this.service = service;
    }

    @Operation(summary = "Consultar contacto de organizador", description = "Obtiene los datos del contacto autorizado asociado a un organizerId.")
    @ApiResponse(responseCode = "200", description = "Contacto encontrado",
            content = @Content(schema = @Schema(implementation = OrganizerContactResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Contacto no registrado para este organizador", content = @Content)
    @ApiResponse(responseCode = "403", description = "Acceso denegado a contactos de otros organizadores", content = @Content)
    @GetMapping("/{organizerId}/contact")
    public ResponseEntity<OrganizerContactResponseDTO> getContact(
            @Parameter(description = "Identificador del organizador", example = "org-ep2-demo-001")
            @PathVariable String organizerId,
            Authentication authentication,
            @AuthenticationPrincipal Jwt jwt) {

        OrganizerContactResponseDTO response = service.getContact(organizerId, authentication, jwt);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Registrar o actualizar contacto de organizador", description = "Crea o actualiza el correo y datos de contacto autorizados para un organizerId.")
    @ApiResponse(responseCode = "200", description = "Contacto actualizado exitosamente",
            content = @Content(schema = @Schema(implementation = OrganizerContactResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Datos de contacto inválidos", content = @Content)
    @ApiResponse(responseCode = "403", description = "Acceso denegado a modificar contactos ajenos", content = @Content)
    @PutMapping("/{organizerId}/contact")
    public ResponseEntity<OrganizerContactResponseDTO> upsertContact(
            @Parameter(description = "Identificador del organizador", example = "org-ep2-demo-001")
            @PathVariable String organizerId,
            @Valid @RequestBody OrganizerContactRequestDTO request,
            Authentication authentication,
            @AuthenticationPrincipal Jwt jwt) {

        OrganizerContactResponseDTO response = service.upsertContact(organizerId, request, authentication, jwt);
        return ResponseEntity.ok(response);
    }
}

