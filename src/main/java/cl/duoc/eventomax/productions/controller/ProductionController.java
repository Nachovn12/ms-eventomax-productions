package cl.duoc.eventomax.productions.controller;

import cl.duoc.eventomax.productions.dto.ProductionRequestDTO;
import cl.duoc.eventomax.productions.dto.ProductionResponseDTO;
import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.service.ProductionService;
import cl.duoc.eventomax.productions.service.ResourceNotFoundException;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

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

    public ProductionController(ProductionService service) {
        this.service = service;
    }


    @Operation(summary = "Crear nueva producción", description = "Crea una solicitud de evento. El estado inicial siempre será SOLICITADO.")
    @ApiResponse(responseCode = "201", description = "Producción creada exitosamente",
                 content = @Content(schema = @Schema(implementation = ProductionResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Error de validación en los campos del request", content = @Content)
    @PostMapping
    public ResponseEntity<ProductionResponseDTO> create(
            @Parameter(description = "Datos de la producción a crear") @Valid @RequestBody ProductionRequestDTO request) {

        ProductionResponseDTO response = service.createProduction(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
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
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

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
            @Parameter(description = "ID de la producción") @PathVariable Long id) {

        return service.getProductionById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException("Produccion no encontrada con id: " + id));

    }


    @Operation(summary = "Actualizar estado de producción", description = "Cambia el estado de una producción validando las reglas de transición. Estados válidos: SOLICITADO, CONFIRMADO, EN_MONTAJE, EN_EJECUCION, CERRADO, CANCELADO.")
    @ApiResponse(responseCode = "200", description = "Estado actualizado exitosamente",
                 content = @Content(schema = @Schema(implementation = ProductionResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Error de validación (estado nulo o inválido)", content = @Content)
    @ApiResponse(responseCode = "404", description = "Producción no encontrada", content = @Content)
    @ApiResponse(responseCode = "409", description = "Conflicto: Transición de estado inválida", content = @Content)
    @PutMapping("/{id}/status")
    public ResponseEntity<ProductionResponseDTO> updateStatus(
            @Parameter(description = "ID de la producción a actualizar") @PathVariable Long id,
            @Parameter(description = "Objeto con el nuevo estado") @Valid @RequestBody ProductionStatusUpdateDTO request) {

        ProductionResponseDTO updated = service.updateStatus(id, request);
        return ResponseEntity.ok(updated);

    }
}
