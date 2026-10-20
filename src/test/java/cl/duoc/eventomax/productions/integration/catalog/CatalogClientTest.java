package cl.duoc.eventomax.productions.integration.catalog;

import cl.duoc.eventomax.productions.dto.ProductionReservationItemDTO;
import cl.duoc.eventomax.productions.integration.catalog.exception.CatalogUnavailableException;
import cl.duoc.eventomax.productions.integration.catalog.exception.InventoryReservationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CatalogClientTest {

    private CatalogClient catalogClient;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl("http://localhost:8081").build();
        catalogClient = new CatalogClient(restClient);
    }

    @Test
    void reserveInventory_success_returns201() {
        mockServer.expect(requestTo("http://localhost:8081/api/catalog/reservations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withCreatedEntity(null));

        List<ProductionReservationItemDTO> items = List.of(new ProductionReservationItemDTO(1L, 2));

        assertDoesNotThrow(() -> catalogClient.reserveInventory(1L, items));
        mockServer.verify();
    }

    @Test
    void reserveInventory_409_throwsInventoryReservationException() {
        mockServer.expect(requestTo("http://localhost:8081/api/catalog/reservations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON));

        List<ProductionReservationItemDTO> items = List.of(new ProductionReservationItemDTO(1L, 2));

        assertThrows(InventoryReservationException.class, () -> catalogClient.reserveInventory(1L, items));
        mockServer.verify();
    }

    @Test
    void reserveInventory_500_throwsCatalogUnavailableException() {
        mockServer.expect(requestTo("http://localhost:8081/api/catalog/reservations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        List<ProductionReservationItemDTO> items = List.of(new ProductionReservationItemDTO(1L, 2));

        assertThrows(CatalogUnavailableException.class, () -> catalogClient.reserveInventory(1L, items));
        mockServer.verify();
    }
}
