package cl.duoc.eventomax.productions.dto;

public enum OutboxRecoveryStatus {
    RECOVERED,
    ALREADY_PENDING,
    IN_PROGRESS,
    ALREADY_SENT,
    BLOCKED_BY_PREDECESSOR,
    NOT_FOUND
}

