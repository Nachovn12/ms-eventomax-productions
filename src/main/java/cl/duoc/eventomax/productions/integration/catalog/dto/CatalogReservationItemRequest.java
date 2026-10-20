package cl.duoc.eventomax.productions.integration.catalog.dto;

public record CatalogReservationItemRequest(
        Long equipmentId,
        Integer quantity
) {}
