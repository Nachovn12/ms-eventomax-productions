package cl.duoc.eventomax.productions.controller;

import cl.duoc.eventomax.productions.dto.EligibleOrganizersPageDTO;
import cl.duoc.eventomax.productions.dto.OrganizerContactRequestDTO;
import cl.duoc.eventomax.productions.dto.OrganizerContactResponseDTO;
import cl.duoc.eventomax.productions.service.EligibleOrganizerService;
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
    private final EligibleOrganizerService eligibleOrganizerService;

    public OrganizerContactController(OrganizerContactService service) {
        this(service, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrganizerContactController(
            OrganizerContactService service,
            @org.springframework.beans.factory.annotation.Autowired(required = false) EligibleOrganizerService eligibleOrganizerService) {
        this.service = service;
        this.eligibleOrganizerService = eligibleOrganizerService;
    }

    @Operation(summary = "Listar organizadores elegibles", description = "Devuelve organizadores habilitados de Entra ID (cuando Graph está activo) y clientes externos registrados para el selector de nueva producción. Restringido a Admin y Productor.")
    @ApiResponse(responseCode = "200", description = "Listado paginado de organizadores elegibles",
            content = @Content(schema = @Schema(implementation = EligibleOrganizersPageDTO.class)))
    @ApiResponse(responseCode = "403", description = "Acceso restringido a Admin y Productor", content = @Content)
    @GetMapping("/eligible")
    public ResponseEntity<EligibleOrganizersPageDTO> listEligible(
            @Parameter(description = "Filtro opcional por nombre") @RequestParam(required = false) String q,
            @Parameter(description = "Número de página (base 0)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 50)") @RequestParam(defaultValue = "20") int size) {
        if (eligibleOrganizerService == null) {
            return ResponseEntity.ok(new EligibleOrganizersPageDTO(
                    java.util.List.of(), Math.max(0, page), Math.min(Math.max(1, size), EligibleOrganizerService.MAX_PAGE_SIZE),
                    0L, cl.duoc.eventomax.productions.dto.DirectorySyncStatus.DISABLED));
        }
        return ResponseEntity.ok(eligibleOrganizerService.listEligibleOrganizers(q, page, size));
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

