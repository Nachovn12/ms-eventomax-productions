package cl.duoc.eventomax.productions.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record OutboxRecoveryResult(
        UUID outboxId,
        OutboxRecoveryStatus status,
        String message,
        Integer previousRetries,
        LocalDateTime updatedAt
) {}

