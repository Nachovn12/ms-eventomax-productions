package cl.duoc.eventomax.productions.integration.catalog.dto;

import java.math.BigDecimal;

public record CatalogServiceDTO(
        Long id,
        String name,
        String description,
        BigDecimal rate,
        Boolean active
) {}
