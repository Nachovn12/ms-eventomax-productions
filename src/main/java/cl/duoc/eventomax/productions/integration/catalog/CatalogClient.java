package cl.duoc.eventomax.productions.integration.catalog;

import cl.duoc.eventomax.productions.dto.ProductionReservationItemDTO;
import cl.duoc.eventomax.productions.integration.catalog.dto.CatalogReservationItemRequest;
import cl.duoc.eventomax.productions.integration.catalog.dto.CatalogReservationRequest;
import cl.duoc.eventomax.productions.integration.catalog.dto.CatalogReservationResponseDTO;
import cl.duoc.eventomax.productions.integration.catalog.exception.CatalogUnavailableException;
import cl.duoc.eventomax.productions.integration.catalog.exception.InventoryReservationException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class CatalogClient {

    private final RestClient catalogRestClient;

    public CatalogClient(RestClient catalogRestClient) {
        this.catalogRestClient = catalogRestClient;
    }

    public void reserveInventory(Long productionId, List<ProductionReservationItemDTO> items) {
        reserveInventory(productionId, items, null);
    }

    public void reserveInventory(Long productionId, List<ProductionReservationItemDTO> items, Long generation) {
        List<CatalogReservationItemRequest> requestItems = items.stream()
                .map(i -> new CatalogReservationItemRequest(i.equipmentId(), i.quantity()))
                .toList();

        CatalogReservationRequest request = new CatalogReservationRequest(productionId, requestItems);

        try {
            catalogRestClient.post()
                    .uri("/api/catalog/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        var trace = cl.duoc.eventomax.productions.tracing.TraceContext.current();
                        headers.set("X-Trace-Id", trace.traceId());
                        headers.set("X-Correlation-Id", trace.correlationId());
                        if (generation != null) {
                            headers.set("X-Reservation-Generation", String.valueOf(generation));
                        }
                    })
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new InventoryReservationException("Inventory reservation rejected by Catalog");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new CatalogUnavailableException("Catalog service is unavailable");
                    })
                    .toBodilessEntity();
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new CatalogUnavailableException("Connection to Catalog service failed");
        }
    }

    public List<CatalogReservationResponseDTO> getReservationsByProduction(Long productionId) {
        try {
            List<CatalogReservationResponseDTO> result = catalogRestClient.get()
                    .uri("/api/catalog/reservations/production/{productionId}", productionId)
                    .headers(headers -> {
                        var trace = cl.duoc.eventomax.productions.tracing.TraceContext.current();
                        headers.set("X-Trace-Id", trace.traceId());
                        headers.set("X-Correlation-Id", trace.correlationId());
                    })
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, res) -> {})
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new CatalogUnavailableException("Catalog service is unavailable");
                    })
                    .body(new ParameterizedTypeReference<List<CatalogReservationResponseDTO>>() {});
            return result != null ? result : List.of();
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new CatalogUnavailableException("Connection to Catalog service failed");
        }
    }

    public void compensateInventory(Long productionId) {
        compensateInventory(productionId, null);
    }

    public void compensateInventory(Long productionId, Long generation) {
        try {
            catalogRestClient.post()
                    .uri("/api/catalog/reservations/production/{productionId}/compensate", productionId)
                    .headers(headers -> {
                        var trace = cl.duoc.eventomax.productions.tracing.TraceContext.current();
                        headers.set("X-Trace-Id", trace.traceId());
                        headers.set("X-Correlation-Id", trace.correlationId());
                        if (generation != null) {
                            headers.set("X-Reservation-Generation", String.valueOf(generation));
                        }
                    })
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new CatalogUnavailableException("Catalog compensation failed: service unavailable");
                    })
                    .toBodilessEntity();
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new CatalogUnavailableException("Connection to Catalog service failed during compensation");
        }
    }
}
