package cl.duoc.eventomax.productions.controller;

import cl.duoc.eventomax.productions.dto.OutboxRecoveryResult;
import cl.duoc.eventomax.productions.dto.OutboxRecoveryStatus;
import cl.duoc.eventomax.productions.service.OutboxManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/productions/admin/outbox")
@Tag(name = "Outbox Admin", description = "Operaciones administrativas de Outbox (Rol Admin requerido)")
public class OutboxAdminController {

    private final OutboxManagementService outboxManagementService;

    public OutboxAdminController(OutboxManagementService outboxManagementService) {
        this.outboxManagementService = outboxManagementService;
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "Reintentar evento outbox en estado ERROR",
               description = "Permite a un administrador rehabilitar un evento fallido respetando el orden causal")
    public ResponseEntity<OutboxRecoveryResult> retryEvent(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {

        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new AccessDeniedException("Identidad requerida");
        }

        String actor = jwt.getSubject();
        OutboxRecoveryResult result = outboxManagementService.retryFailedEvent(id, actor);

        if (result.status() == OutboxRecoveryStatus.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        }

        if (result.status() == OutboxRecoveryStatus.RECOVERED) {
            return ResponseEntity.ok(result);
        }

        // ALREADY_PENDING, IN_PROGRESS, ALREADY_SENT, BLOCKED_BY_PREDECESSOR return 409 Conflict
        return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }
}

