package cl.duoc.eventomax.productions.controller;

import cl.duoc.eventomax.productions.dto.ProductionRequestDTO;
import cl.duoc.eventomax.productions.dto.ProductionResponseDTO;
import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.service.ProductionService;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/productions")
public class ProductionController {

    private final ProductionService service;

    public ProductionController(ProductionService service) {
        this.service = service;
    }


    @PostMapping
    public ResponseEntity<ProductionResponseDTO> create(
            @Valid @RequestBody ProductionRequestDTO request) {

        ProductionResponseDTO response = service.createProduction(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }


    @GetMapping
    public ResponseEntity<List<ProductionResponseDTO>> getAll(
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        if (status == null && from == null && to == null) {
            return ResponseEntity.ok(service.getAllProductions());
        }

        return ResponseEntity.ok(service.getFilteredProductions(status, from, to));

    }


    @GetMapping("/{id}")
    public ResponseEntity<ProductionResponseDTO> getById(
            @PathVariable Long id) {

        return service.getProductionById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());

    }


    @PutMapping("/{id}/status")
    public ResponseEntity<ProductionResponseDTO> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody ProductionStatusUpdateDTO request) {

        ProductionResponseDTO updated = service.updateStatus(id, request);
        return ResponseEntity.ok(updated);

    }
}
