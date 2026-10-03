package cl.duoc.eventomax.productions.integration.catalog;

import cl.duoc.eventomax.productions.dto.ProductionReservationItemDTO;
import cl.duoc.eventomax.productions.integration.catalog.dto.CatalogReservationItemRequest;
import cl.duoc.eventomax.productions.integration.catalog.dto.CatalogReservationRequest;
import cl.duoc.eventomax.productions.integration.catalog.exception.CatalogUnavailableException;
import cl.duoc.eventomax.productions.integration.catalog.exception.InventoryReservationException;
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
        List<CatalogReservationItemRequest> requestItems = items.stream()
                .map(i -> new CatalogReservationItemRequest(i.equipmentId(), i.quantity()))
                .toList();

        CatalogReservationRequest request = new CatalogReservationRequest(productionId, requestItems);

        try {
            catalogRestClient.post()
                    .uri("/api/catalog/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
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
}
