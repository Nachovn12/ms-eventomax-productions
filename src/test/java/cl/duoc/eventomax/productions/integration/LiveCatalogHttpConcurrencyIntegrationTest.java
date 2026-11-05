package cl.duoc.eventomax.productions.integration;

import cl.duoc.eventomax.productions.dto.ProductionRequestDTO;
import cl.duoc.eventomax.productions.dto.ProductionReservationItemDTO;
import cl.duoc.eventomax.productions.dto.ProductionResponseDTO;
import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.service.ProductionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_LIVE_CATALOG_HTTP", matches = "true")
public class LiveCatalogHttpConcurrencyIntegrationTest {

    private static final String CATALOG_URL = "http://localhost:8092";

    @Autowired
    private ProductionService productionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private RestClient catalogHttp;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5499/eventomax_productions");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("eventomax.clients.catalog.base-url", () -> CATALOG_URL);
    }

    @BeforeEach
    void setUp() {
        catalogHttp = RestClient.builder().baseUrl(CATALOG_URL).build();

        Jwt jwt = Jwt.withTokenValue("mock-live-token")
                .header("alg", "none")
                .claim("sub", "organizer-live-test")
                .claim("roles", List.of("Admin", "Productor"))
                .claim("scp", "access_as_user")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_Admin"),
                new SimpleGrantedAuthority("ROLE_Productor"),
                new SimpleGrantedAuthority("SCOPE_access_as_user")
        );

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, authorities, "organizer-live-test")
        );
    }

    @Test
    @DisplayName("Real HTTP E2E: Concurrencia de confirmación, retención de stock, cancelación, tombstones y bloqueo legacy")
    void testRealHttpConcurrencyAndCancellationFlow() throws Exception {
        // 1. Verificar stock inicial de equipos 1 y 2 en Catalog vía HTTP real
        JsonNode eq1Initial = fetchEquipment(1L);
        int eq1InitialAvail = eq1Initial.get("availableQty").asInt();
        int eq1InitialRes = eq1Initial.get("reservedQty").asInt();

        JsonNode eq2Initial = fetchEquipment(2L);
        int eq2InitialAvail = eq2Initial.get("availableQty").asInt();
        int eq2InitialRes = eq2Initial.get("reservedQty").asInt();

        assertThat(eq1InitialAvail).isGreaterThanOrEqualTo(4);
        assertThat(eq2InitialAvail).isGreaterThanOrEqualTo(2);

        // 2. Crear una nueva producción en estado SOLICITADO
        ProductionRequestDTO createDTO = new ProductionRequestDTO(
                "organizer-live-test",
                "Concierto Sinfonico Live HTTP",
                LocalDateTime.now().plusDays(15),
                "Teatro Municipal Santiago"
        );
        ProductionResponseDTO created = productionService.createProduction(createDTO, "organizer-live-test");
        Long prodId = created.id();
        assertNotNull(prodId);
        assertEquals(ProductionStatus.SOLICITADO.name(), created.status());

        // 3. Preparar DTO de confirmación con 4 de equipo 1 y 2 de equipo 2
        List<ProductionReservationItemDTO> items = List.of(
                new ProductionReservationItemDTO(1L, 4),
                new ProductionReservationItemDTO(2L, 2)
        );
        ProductionStatusUpdateDTO confirmDTO = new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO, items);

        // 4. Ejecutar confirmación CONCURRENTE con dos hilos entrando simultáneamente
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final String actorName = "actor-concurrent-" + i;
            executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    ProductionResponseDTO res = productionService.updateStatus(prodId, confirmDTO, actorName);
                    if (ProductionStatus.CONFIRMADO.name().equalsIgnoreCase(res.status())) {
                        successes.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        boolean finishedInTime = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(finishedInTime).isTrue();

        // 5. Al menos una petición completó la confirmación
        assertThat(successes.get()).isGreaterThanOrEqualTo(1);

        // 6. Verificar que la producción en Productions está CONFIRMADA
        ProductionResponseDTO currentProd = productionService.getProductionById(prodId).orElseThrow();
        assertEquals(ProductionStatus.CONFIRMADO.name(), currentProd.status());

        // 7. VERIFICACIÓN CRÍTICA EN CATALOG VÍA HTTP REAL:
        // Las reservas deben estar EXACTAMENTE en 4 y 2. No debe haber doble decremento ni compensación indebida.
        JsonNode eq1AfterConfirm = fetchEquipment(1L);
        assertEquals(eq1InitialAvail - 4, eq1AfterConfirm.get("availableQty").asInt(),
                "Stock disponible de equipo 1 debe haber disminuido exactamente 4");
        assertEquals(eq1InitialRes + 4, eq1AfterConfirm.get("reservedQty").asInt(),
                "Stock reservado de equipo 1 debe haber aumentado exactamente 4");

        JsonNode eq2AfterConfirm = fetchEquipment(2L);
        assertEquals(eq2InitialAvail - 2, eq2AfterConfirm.get("availableQty").asInt(),
                "Stock disponible de equipo 2 debe haber disminuido exactamente 2");
        assertEquals(eq2InitialRes + 2, eq2AfterConfirm.get("reservedQty").asInt(),
                "Stock reservado de equipo 2 debe haber aumentado exactamente 2");

        // 8. CANCELACIÓN: Transicionar de CONFIRMADO a CANCELADO
        ProductionStatusUpdateDTO cancelDTO = new ProductionStatusUpdateDTO(ProductionStatus.CANCELADO, null);
        ProductionResponseDTO cancelled = productionService.updateStatus(prodId, cancelDTO, "actor-admin-cancel");
        assertEquals(ProductionStatus.CANCELADO.name(), cancelled.status());

        // 9. VERIFICACIÓN DE COMPENSACIÓN EN CATALOG VÍA HTTP REAL:
        // Todo el inventario debe haber regresado intacto a los valores iniciales.
        JsonNode eq1AfterCancel = fetchEquipment(1L);
        assertEquals(eq1InitialAvail, eq1AfterCancel.get("availableQty").asInt(),
                "Stock disponible de equipo 1 debe haber vuelto a su valor original");
        assertEquals(eq1InitialRes, eq1AfterCancel.get("reservedQty").asInt(),
                "Stock reservado de equipo 1 debe haber vuelto a 0");

        JsonNode eq2AfterCancel = fetchEquipment(2L);
        assertEquals(eq2InitialAvail, eq2AfterCancel.get("availableQty").asInt(),
                "Stock disponible de equipo 2 debe haber vuelto a su valor original");
        assertEquals(eq2InitialRes, eq2AfterCancel.get("reservedQty").asInt(),
                "Stock reservado de equipo 2 debe haber vuelto a 0");

        // 10. VERIFICACIÓN DE FENCING / TOMBSTONES DIRECTO EN CATALOG:
        // Una petición retrasada con generación 1 debe ser RECHAZADA con 409 Conflict.
        String delayedPayload = String.format("""
                {
                    "productionId": %d,
                    "items": [{"equipmentId": 1, "quantity": 4}]
                }
                """, prodId);

        var delayedResponse = catalogHttp.post()
                .uri("/api/catalog/reservations")
                .header("X-Reservation-Generation", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(delayedPayload)
                .exchange((req, res) -> res.getStatusCode());

        assertEquals(HttpStatusCode.valueOf(409), delayedResponse,
                "Catalog debe rechazar con 409 una reserva retrasada cuya generacion ya fue cancelada/cerrada (tombstone)");

        // 11. VERIFICACIÓN DE BLOQUEO LEGACY SIN CABECERA:
        // Una solicitud legacy sin cabecera X-Reservation-Generation para una producción con generaciones previas debe ser rechazada con 409.
        var legacyResponse = catalogHttp.post()
                .uri("/api/catalog/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(delayedPayload)
                .exchange((req, res) -> res.getStatusCode());

        assertEquals(HttpStatusCode.valueOf(409), legacyResponse,
                "Catalog debe rechazar con 409 una solicitud legacy sin cabecera cuando existen generaciones previas");

        // 12. COMPENSACIÓN DUPLICADA / OBSOLETA:
        // Enviar otra compensación no debe alterar el stock (debe ser idempotente y segura)
        var duplicateCompensate = catalogHttp.post()
                .uri("/api/catalog/reservations/production/{id}/compensate", prodId)
                .header("X-Reservation-Generation", "1")
                .exchange((req, res) -> res.getStatusCode());

        assertThat(duplicateCompensate.is2xxSuccessful()).isTrue();

        // Verificar que el stock sigue intacto tras compensación redundante
        JsonNode eq1Final = fetchEquipment(1L);
        assertEquals(eq1InitialAvail, eq1Final.get("availableQty").asInt());
        assertEquals(eq1InitialRes, eq1Final.get("reservedQty").asInt());
    }

    private JsonNode fetchEquipment(Long id) throws Exception {
        String json = catalogHttp.get()
                .uri("/api/catalog/equipment/{id}", id)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(json);
    }
}
