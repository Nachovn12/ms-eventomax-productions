package cl.duoc.eventomax.productions.controller;

import cl.duoc.eventomax.productions.dto.ContractedServiceDto;
import cl.duoc.eventomax.productions.dto.ExternalOrganizerType;
import cl.duoc.eventomax.productions.dto.OrganizerMode;
import cl.duoc.eventomax.productions.dto.ProductionRequestDTO;
import cl.duoc.eventomax.productions.dto.ProductionResponseDTO;
import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.service.ContactConflictException;
import cl.duoc.eventomax.productions.service.EligibleOrganizerCandidate;
import cl.duoc.eventomax.productions.service.EligibleOrganizerService;
import cl.duoc.eventomax.productions.service.ProductionService;
import cl.duoc.eventomax.productions.service.ResourceNotFoundException;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;

@RestController
@RequestMapping("/api/productions")
@Tag(name = "Productions", description = "API para la gestión del ciclo de vida de las producciones (eventos)")
public class ProductionController {

    private final ProductionService service;
    private final cl.duoc.eventomax.productions.service.OrganizerContactService organizerContactService;
    private final EligibleOrganizerService eligibleOrganizerService;

    public ProductionController(ProductionService service,
                                cl.duoc.eventomax.productions.service.OrganizerContactService organizerContactService) {
        this(service, organizerContactService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductionController(ProductionService service,
                                cl.duoc.eventomax.productions.service.OrganizerContactService organizerContactService,
                                @org.springframework.beans.factory.annotation.Autowired(required = false) EligibleOrganizerService eligibleOrganizerService) {
        this.service = service;
        this.organizerContactService = organizerContactService;
        this.eligibleOrganizerService = eligibleOrganizerService;
    }


    @Operation(summary = "Crear nueva producción", description = "Crea una solicitud de evento. El estado inicial siempre será SOLICITADO.")
    @ApiResponse(responseCode = "201", description = "Producción creada exitosamente",
                 content = @Content(schema = @Schema(implementation = ProductionResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Error de validación en los campos del request", content = @Content)
    @PostMapping
    public ResponseEntity<ProductionResponseDTO> create(
            @Parameter(description = "Datos de la producción a crear") @Valid @RequestBody ProductionRequestDTO request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt,
            org.springframework.security.core.Authentication authentication) {

        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException("Identidad requerida");
        }
        String actor = cl.duoc.eventomax.productions.security.SecurityUtils.resolveOwnerId(jwt);
        boolean organizerOnly = cl.duoc.eventomax.productions.security.SecurityUtils.isOrganizerOnly(authentication, jwt);
        OrganizerMode mode = request.organizerMode();

        if (mode == OrganizerMode.SELF) {
            if (hasAnyExternalFields(request)) {
                throw new IllegalArgumentException(
                        "No se permiten campos de cliente externo cuando organizerMode=SELF");
            }
            String canonicalOwnerId = cl.duoc.eventomax.productions.security.SecurityUtils.requireOrganizerOwnerId(jwt);
            if (request.organizerId() != null && !request.organizerId().isBlank()
                    && !canonicalOwnerId.equalsIgnoreCase(request.organizerId().trim())) {
                throw new IllegalArgumentException(
                        "En modo SELF el identificador de organizador no puede diferir de la identidad autenticada");
            }
            ProductionRequestDTO selfRequest = new ProductionRequestDTO(
                    canonicalOwnerId,
                    request.name(),
                    request.scheduledAt(),
                    request.location(),
                    request.crewId(),
                    request.crewName(),
                    OrganizerMode.SELF
            );
            ProductionResponseDTO response = service.createProduction(selfRequest, canonicalOwnerId, jwt);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }

        if (mode == OrganizerMode.EXTERNAL) {
            if (organizerOnly) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Organizador no puede crear producciones en modo EXTERNAL");
            }
            return handleExternalCreation(request, actor);
        }

        // Ruta legacy (organizerMode == null): rechazar campos exclusivos del contrato EXTERNAL
        if (hasAnyExternalFields(request)) {
            throw new IllegalArgumentException(
                    "Los campos de cliente externo requieren especificar organizerMode=EXTERNAL");
        }

        ProductionRequestDTO effectiveRequest = request;
        if (organizerOnly) {
            String targetOrganizerId = (request.organizerId() != null && !request.organizerId().isBlank())
                    ? request.organizerId().trim()
                    : null;
            if (targetOrganizerId != null) {
                if (!organizerContactService.isAuthorizedForOrganizer(targetOrganizerId, authentication, jwt)) {
                    throw new org.springframework.security.access.AccessDeniedException(
                            "No esta autorizado para crear producciones en nombre de este organizador");
                }
                // Normalize only the canonical identity already verified against this caller's JWT.
                // Business aliases retain their original identifier and ownership rules.
                if (cl.duoc.eventomax.productions.security.SecurityUtils.isCanonicalOwnerId(targetOrganizerId)) {
                    targetOrganizerId = cl.duoc.eventomax.productions.security.SecurityUtils.requireOrganizerOwnerId(jwt);
                }
            } else {
                targetOrganizerId = organizerContactService.resolveAuthorizedOrganizerIds(jwt).stream()
                        .findFirst()
                        .orElse(actor);
            }
            effectiveRequest = new ProductionRequestDTO(
                    targetOrganizerId,
                    request.name(),
                    request.scheduledAt(),
                    request.location(),
                    request.crewId(),
                    request.crewName(),
                    mode
            );
        } else {
            // Protección anti-bypass para Productor en ruta legacy:
            // Si organizerId es una identidad canónica Entra (tid:oid) distinta a la del llamador,
            // exige verificación server-side de elegibilidad y cuenta habilitada (fail-closed).
            String legacyOrgId = (request.organizerId() != null) ? request.organizerId().trim() : "";
            if (cl.duoc.eventomax.productions.security.SecurityUtils.isCanonicalOwnerId(legacyOrgId)) {
                String callerOwnerId = cl.duoc.eventomax.productions.security.SecurityUtils.requireOrganizerOwnerId(jwt);
                if (callerOwnerId.equalsIgnoreCase(legacyOrgId)) {
                    effectiveRequest = new ProductionRequestDTO(
                            callerOwnerId,
                            request.name(),
                            request.scheduledAt(),
                            request.location(),
                            request.crewId(),
                            request.crewName(),
                            null
                    );
                } else {
                    if (eligibleOrganizerService == null) {
                        throw new cl.duoc.eventomax.productions.service.ExternalDirectoryUnavailableException(
                                "Servicio de verificación de organizadores Entra ID no disponible.");
                    }
                    EligibleOrganizerCandidate verified = eligibleOrganizerService.requireVerifiedEntraOrganizer(legacyOrgId);
                    effectiveRequest = new ProductionRequestDTO(
                            verified.organizerId(),
                            request.name(),
                            request.scheduledAt(),
                            request.location(),
                            request.crewId(),
                            request.crewName(),
                            null
                    );
                }
            }
        }

        ProductionResponseDTO response = service.createProduction(effectiveRequest, actor);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    private static boolean hasAnyExternalFields(ProductionRequestDTO request) {
        return request.externalOrganizerType() != null
                || (request.externalClientName() != null && !request.externalClientName().isBlank())
                || (request.externalClientEmail() != null && !request.externalClientEmail().isBlank());
    }

    private ResponseEntity<ProductionResponseDTO> handleExternalCreation(ProductionRequestDTO request, String actor) {
        ExternalOrganizerType extType = request.externalOrganizerType();
        boolean hasEmailInput = request.externalClientEmail() != null && !request.externalClientEmail().isBlank();
        boolean hasNameInput = request.externalClientName() != null && !request.externalClientName().isBlank();

        // Caso C: Nuevo cliente externo con identificador opaco generado por el backend (ext-<uuid>)
        if (extType == ExternalOrganizerType.NEW_EXTERNAL) {
            if (request.organizerId() != null && !request.organizerId().isBlank()) {
                throw new IllegalArgumentException(
                        "No se permite asignar manualmente un organizerId al registrar un nuevo cliente externo.");
            }
            if (!hasNameInput) {
                throw new IllegalArgumentException("El nombre del cliente externo es obligatorio.");
            }
            if (!hasEmailInput || !cl.duoc.eventomax.productions.security.SecurityUtils.isDeliverableEmail(request.externalClientEmail())) {
                throw new IllegalArgumentException("Se requiere un correo electrónico válido para registrar un nuevo cliente externo.");
            }
            String generatedOrganizerId = "ext-" + java.util.UUID.randomUUID();
            ProductionRequestDTO effective = new ProductionRequestDTO(
                    generatedOrganizerId,
                    request.name(),
                    request.scheduledAt(),
                    request.location(),
                    request.crewId(),
                    request.crewName(),
                    OrganizerMode.EXTERNAL,
                    ExternalOrganizerType.NEW_EXTERNAL,
                    request.externalClientName().trim(),
                    request.externalClientEmail().trim()
            );
            var provision = new ProductionService.ContactProvisionRequest(
                    generatedOrganizerId,
                    request.externalClientEmail().trim(),
                    request.externalClientName().trim(),
                    null
            );
            ProductionResponseDTO created = service.createProductionWithContact(effective, actor, provision);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        }

        String targetOrganizerId = (request.organizerId() != null) ? request.organizerId().trim() : "";
        if (targetOrganizerId.isBlank()) {
            throw new IllegalArgumentException("El organizador es obligatorio en modo EXTERNAL.");
        }

        // Caso A: Organizador Entra ID (explícito o identificado por formato canónico tid:oid)
        if (extType == ExternalOrganizerType.ENTRA_ORGANIZER
                || (extType == null && cl.duoc.eventomax.productions.security.SecurityUtils.isCanonicalOwnerId(targetOrganizerId))) {
            if (hasEmailInput) {
                throw new ContactConflictException(
                        "No se permite proporcionar o sobrescribir un correo manual para un organizador de Microsoft Entra ID.");
            }
            if (hasNameInput) {
                throw new IllegalArgumentException(
                        "El campo externalClientName solo se permite cuando externalOrganizerType=NEW_EXTERNAL.");
            }
            if (eligibleOrganizerService == null) {
                throw new cl.duoc.eventomax.productions.service.ExternalDirectoryUnavailableException(
                        "Servicio de verificación de organizadores Entra ID no disponible.");
            }
            EligibleOrganizerCandidate verified = eligibleOrganizerService.requireVerifiedEntraOrganizer(targetOrganizerId);
            ProductionRequestDTO effective = new ProductionRequestDTO(
                    verified.organizerId(),
                    request.name(),
                    request.scheduledAt(),
                    request.location(),
                    request.crewId(),
                    request.crewName(),
                    OrganizerMode.EXTERNAL,
                    ExternalOrganizerType.ENTRA_ORGANIZER,
                    null,
                    null
            );
            ProductionService.ContactProvisionRequest provision = (verified.deliverableEmail() != null)
                    ? new ProductionService.ContactProvisionRequest(
                            verified.organizerId(),
                            verified.deliverableEmail(),
                            verified.displayName(),
                            verified.userOid())
                    : null;
            ProductionResponseDTO created = service.createProductionWithContact(effective, actor, provision);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        }

        // Caso B: Cliente externo existente (explícito)
        if (extType == ExternalOrganizerType.EXISTING_EXTERNAL) {
            if (cl.duoc.eventomax.productions.security.SecurityUtils.isCanonicalOwnerId(targetOrganizerId)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "No se permite tratar una identidad canónica de Entra ID como cliente externo.");
            }
            if (hasEmailInput) {
                throw new ContactConflictException(
                        "No se permite modificar el correo de un cliente externo existente desde la creación de producción.");
            }
            if (hasNameInput) {
                throw new IllegalArgumentException(
                        "El campo externalClientName solo se permite cuando externalOrganizerType=NEW_EXTERNAL.");
            }
            if (eligibleOrganizerService == null) {
                throw new ResourceNotFoundException("Cliente externo no encontrado: " + targetOrganizerId);
            }
            EligibleOrganizerCandidate existing = eligibleOrganizerService.requireExistingExternalOrganizer(targetOrganizerId);
            ProductionRequestDTO effective = new ProductionRequestDTO(
                    existing.organizerId(),
                    request.name(),
                    request.scheduledAt(),
                    request.location(),
                    request.crewId(),
                    request.crewName(),
                    OrganizerMode.EXTERNAL,
                    ExternalOrganizerType.EXISTING_EXTERNAL,
                    null,
                    null
            );
            ProductionResponseDTO created = service.createProduction(effective, actor);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        }

        // Compatibilidad legacy (EXTERNAL sin externalOrganizerType): preserva flujo histórico sin sobrescribir contactos
        if (hasEmailInput) {
            throw new ContactConflictException(
                    "Para registrar un cliente nuevo con correo utilice externalOrganizerType=NEW_EXTERNAL.");
        }
        ProductionResponseDTO created = service.createProduction(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }


    @Operation(summary = "Listar producciones", description = "Obtiene el listado de todas las producciones. Permite filtrar opcionalmente por estado y rango de fechas.")
    @ApiResponse(responseCode = "200", description = "Listado obtenido exitosamente")
    @ApiResponse(responseCode = "400", description = "Estado o rango de fechas inválido", content = @Content)
    @GetMapping
    public ResponseEntity<List<ProductionResponseDTO>> getAll(
            @Parameter(description = "Filtro por estado de la producción (Ej: CONFIRMADO)") @RequestParam(required = false) String status,
            @Parameter(description = "Fecha de inicio para el filtro (formato ISO-8601)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "Fecha de fin para el filtro (formato ISO-8601)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt,
            org.springframework.security.core.Authentication authentication) {

        boolean organizerOnly = cl.duoc.eventomax.productions.security.SecurityUtils.isOrganizerOnly(authentication, jwt);

        if (organizerOnly) {
            java.util.List<String> authorizedOrganizerIds = organizerContactService.resolveAuthorizedOrganizerIds(jwt);
            return ResponseEntity.ok(service.getFilteredProductionsForOrganizers(status, from, to, authorizedOrganizerIds));
        }

        if (status == null && from == null && to == null) {
            return ResponseEntity.ok(service.getAllProductions());
        }

        return ResponseEntity.ok(service.getFilteredProductions(status, from, to));

    }


    @Operation(summary = "Obtener producción por ID", description = "Consulta el detalle de una producción específica usando su identificador único.")
    @ApiResponse(responseCode = "200", description = "Producción encontrada",
                 content = @Content(schema = @Schema(implementation = ProductionResponseDTO.class)))
    @ApiResponse(responseCode = "404", description = "Producción no encontrada", content = @Content)
    @GetMapping("/{id}")
    public ResponseEntity<ProductionResponseDTO> getById(
            @Parameter(description = "ID de la producción") @PathVariable Long id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt,
            org.springframework.security.core.Authentication authentication) {

        ProductionResponseDTO production = service.getProductionById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produccion no encontrada con id: " + id));

        if (cl.duoc.eventomax.productions.security.SecurityUtils.isOrganizerOnly(authentication, jwt)) {
            if (!organizerContactService.isAuthorizedForOrganizer(production.organizerId(), authentication, jwt)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "No tiene permisos para acceder a esta produccion");
            }
        }

        return ResponseEntity.ok(production);

    }


    @Operation(summary = "Consultar servicios contratados de una producción", description = "Devuelve los servicios contratados asociados a la producción, aplicando aislamiento por organizador.")
    @ApiResponse(responseCode = "200", description = "Listado de servicios contratados")
    @ApiResponse(responseCode = "403", description = "Sin permisos para consultar esta producción", content = @Content)
    @ApiResponse(responseCode = "404", description = "Producción no encontrada", content = @Content)
    @GetMapping("/{id}/services")
    public ResponseEntity<List<ContractedServiceDto>> getContractedServices(
            @Parameter(description = "ID de la producción") @PathVariable Long id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt,
            org.springframework.security.core.Authentication authentication) {

        ProductionResponseDTO production = service.getProductionById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produccion no encontrada con id: " + id));

        if (cl.duoc.eventomax.productions.security.SecurityUtils.isOrganizerOnly(authentication, jwt)) {
            if (!organizerContactService.isAuthorizedForOrganizer(production.organizerId(), authentication, jwt)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "No tiene permisos para acceder a esta produccion");
            }
        }

        return ResponseEntity.ok(service.getContractedServices(id));
    }


    @Operation(summary = "Actualizar estado de producción", description = "Cambia el estado de una producción validando las reglas de transición. Estados válidos: SOLICITADO, CONFIRMADO, EN_MONTAJE, EN_EJECUCION, CERRADO, CANCELADO.")
    @ApiResponse(responseCode = "200", description = "Estado actualizado exitosamente",
                 content = @Content(schema = @Schema(implementation = ProductionResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Error de validación (estado nulo o inválido)", content = @Content)
    @ApiResponse(responseCode = "404", description = "Producción no encontrada", content = @Content)
    @ApiResponse(responseCode = "409", description = "Conflicto: Transición de estado inválida o reserva de inventario rechazada", content = @Content)
    @ApiResponse(responseCode = "503", description = "Catalog temporalmente no disponible", content = @Content)
    @PutMapping("/{id}/status")
    public ResponseEntity<ProductionResponseDTO> updateStatus(
            @Parameter(description = "ID de la producción a actualizar") @PathVariable Long id,
            @Parameter(description = "Objeto con el nuevo estado") @Valid @RequestBody ProductionStatusUpdateDTO request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt,
            org.springframework.security.core.Authentication authentication) {

        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException("Identidad requerida");
        }

        if (cl.duoc.eventomax.productions.security.SecurityUtils.isOrganizerOnly(authentication, jwt)) {
            ProductionResponseDTO existing = service.getProductionById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Produccion no encontrada con id: " + id));
            if (!organizerContactService.isAuthorizedForOrganizer(existing.organizerId(), authentication, jwt)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "No tiene permisos para modificar esta produccion");
            }
        }

        String actor = jwt.getSubject();
        ProductionResponseDTO updated = service.updateStatus(id, request, actor);
        return ResponseEntity.ok(updated);

    }


    @Operation(summary = "Republicar comando de acta PDF (re-drive operativo)", description = "Republica el comando enriquecido GenerateClosingQuote hacia RabbitMQ para una producción en estado CERRADO. Restringido a Admin y Productor.")
    @ApiResponse(responseCode = "200", description = "Comando republicado exitosamente")
    @ApiResponse(responseCode = "403", description = "Sin autorización administrativa/productor", content = @Content)
    @ApiResponse(responseCode = "404", description = "Producción no encontrada", content = @Content)
    @ApiResponse(responseCode = "409", description = "La producción no se encuentra en estado CERRADO", content = @Content)
    @PostMapping("/{id}/quote-command/republish")
    public ResponseEntity<Map<String, Object>> republishQuoteCommand(
            @Parameter(description = "ID de la producción cerrada") @PathVariable Long id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt,
            org.springframework.security.core.Authentication authentication) {

        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException("Identidad requerida");
        }
        if (cl.duoc.eventomax.productions.security.SecurityUtils.isOrganizerOnly(authentication, jwt)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Solo Admin o Productor pueden republicar el comando de acta PDF");
        }

        String actor = cl.duoc.eventomax.productions.security.SecurityUtils.resolveOwnerId(jwt);
        boolean published = service.republishQuoteCommand(id, actor);
        return ResponseEntity.ok(Map.of(
                "productionId", id,
                "status", "CERRADO",
                "command", "GenerateClosingQuote",
                "republished", published
        ));
    }
}
