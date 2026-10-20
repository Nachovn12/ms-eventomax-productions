package cl.duoc.eventomax.productions.integration.catalog.dto;

import java.util.List;

public record CatalogReservationRequest(
        Long productionId,
        List<CatalogReservationItemRequest> items
) {}
