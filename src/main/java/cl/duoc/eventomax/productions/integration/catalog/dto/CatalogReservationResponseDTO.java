package cl.duoc.eventomax.productions.integration.catalog.dto;

public record CatalogReservationResponseDTO(
    Long reservationId,
    Long equipmentId,
    String equipmentName,
    Integer quantity,
    String status
) {}
