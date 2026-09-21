package cl.duoc.eventomax.productions;

import cl.duoc.eventomax.productions.dto.ProductionResponseDTO;
import cl.duoc.eventomax.productions.dto.ProductionStatusUpdateDTO;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.service.InvalidTransitionException;
import cl.duoc.eventomax.productions.service.ProductionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
class ProductionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ProductionService service;

    // ObjectMapper construido localmente para no depender del contexto @WebMvcTest
    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    private ProductionResponseDTO sampleResponse() {
        LocalDateTime now = LocalDateTime.now();
        return new ProductionResponseDTO(
                1L,
                "organizer-abc",
                "Evento corporativo",
                LocalDateTime.of(2027, 6, 15, 18, 0),
                "Santiago",
                "SOLICITADO",
                now,
                now
        );
    }

    // ------------------------------------------------------------------
    // Test 1 – GET /api/productions devuelve lista
    // ------------------------------------------------------------------

    @Test
    void getAll_returnsOkWithList() throws Exception {

        given(service.getAllProductions()).willReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/productions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Evento corporativo"))
                .andExpect(jsonPath("$[0].status").value("SOLICITADO"));
    }

    // ------------------------------------------------------------------
    // Test 2 – GET /api/productions/{id} existente → 200
    // ------------------------------------------------------------------

    @Test
    void getById_existingId_returnsOk() throws Exception {

        given(service.getProductionById(1L)).willReturn(Optional.of(sampleResponse()));

        mockMvc.perform(get("/api/productions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.organizerId").value("organizer-abc"))
                .andExpect(jsonPath("$.name").value("Evento corporativo"))
                .andExpect(jsonPath("$.status").value("SOLICITADO"));
    }

    // ------------------------------------------------------------------
    // Test 3 – GET /api/productions/{id} inexistente → 404
    // ------------------------------------------------------------------

    @Test
    void getById_missingId_returns404() throws Exception {

        given(service.getProductionById(99L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/productions/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.message").isString());
    }

    // ------------------------------------------------------------------
    // Test 4 – POST con datos inválidos → 400
    // ------------------------------------------------------------------

    @Test
    void create_invalidRequest_returns400() throws Exception {

        // Body vacío: viola @NotBlank en name/organizerId/location y @NotNull en scheduledAt
        mockMvc.perform(post("/api/productions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").isString());
    }

    // ------------------------------------------------------------------
    // Test 5 – PUT transición válida → 200
    // ------------------------------------------------------------------

    @Test
    void updateStatus_validTransition_returnsOk() throws Exception {

        LocalDateTime now = LocalDateTime.now();
        ProductionResponseDTO confirmed = new ProductionResponseDTO(
                1L, "organizer-abc", "Evento corporativo",
                LocalDateTime.of(2027, 6, 15, 18, 0),
                "Santiago", "CONFIRMADO", now, now
        );
        given(service.updateStatus(eq(1L), any())).willReturn(confirmed);

        String body = objectMapper.writeValueAsString(
                new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO));

        mockMvc.perform(put("/api/productions/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMADO"));
    }

    // ------------------------------------------------------------------
    // Test 5b – PUT producción inexistente → 404
    // ------------------------------------------------------------------

    @Test
    void updateStatus_missingProduction_returns404() throws Exception {

        given(service.updateStatus(eq(99L), any()))
                .willThrow(new cl.duoc.eventomax.productions.service.ResourceNotFoundException("Producción no encontrada"));

        String body = objectMapper.writeValueAsString(
                new ProductionStatusUpdateDTO(ProductionStatus.CONFIRMADO));

        mockMvc.perform(put("/api/productions/99/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    // ------------------------------------------------------------------
    // Test 6 – PUT transición inválida → 409
    // ------------------------------------------------------------------

    @Test
    void updateStatus_invalidTransition_returns409() throws Exception {

        given(service.updateStatus(eq(1L), any()))
                .willThrow(new InvalidTransitionException(
                        "Transicion invalida: SOLICITADO -> EN_MONTAJE. "
                        + "Transiciones permitidas desde SOLICITADO: [CONFIRMADO, CANCELADO]"));

        String body = objectMapper.writeValueAsString(
                new ProductionStatusUpdateDTO(ProductionStatus.EN_MONTAJE));

        mockMvc.perform(put("/api/productions/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").isString());
    }

    // ------------------------------------------------------------------
    // Test 7 – GET /api/productions?status=CONFIRMADO → filtro por status
    // ------------------------------------------------------------------

    @Test
    void getAll_withStatusFilter_returnsFilteredList() throws Exception {

        LocalDateTime now = LocalDateTime.now();
        ProductionResponseDTO confirmed = new ProductionResponseDTO(
                2L, "organizer-xyz", "Concierto",
                LocalDateTime.of(2026, 1, 15, 20, 0),
                "Valparaíso", "CONFIRMADO", now, now
        );
        given(service.getFilteredProductions(eq("CONFIRMADO"), eq(null), eq(null)))
                .willReturn(List.of(confirmed));

        mockMvc.perform(get("/api/productions")
                        .param("status", "CONFIRMADO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].status").value("CONFIRMADO"));
    }

    // ------------------------------------------------------------------
    // Test 7b – GET /api/productions?status=NO_EXISTE → filtro por status inválido 400
    // ------------------------------------------------------------------

    @Test
    void getAll_withInvalidStatusFilter_returns400() throws Exception {
        given(service.getFilteredProductions(eq("NO_EXISTE"), eq(null), eq(null)))
                .willThrow(new IllegalArgumentException("Estado de producción no válido: NO_EXISTE"));

        mockMvc.perform(get("/api/productions")
                        .param("status", "NO_EXISTE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").isString());
    }

    // ------------------------------------------------------------------
    // Test 8 – GET /api/productions?status=CONFIRMADO&from=...&to=...
    // ------------------------------------------------------------------

    @Test
    void getAll_withAllFilters_returnsFilteredList() throws Exception {

        LocalDateTime now = LocalDateTime.now();
        ProductionResponseDTO confirmed = new ProductionResponseDTO(
                3L, "organizer-xyz", "Feria de arte",
                LocalDateTime.of(2026, 1, 20, 10, 0),
                "Concepción", "CONFIRMADO", now, now
        );
        given(service.getFilteredProductions(
                eq("CONFIRMADO"),
                eq(LocalDateTime.of(2026, 1, 1, 0, 0, 0)),
                eq(LocalDateTime.of(2026, 1, 31, 23, 59, 59))))
                .willReturn(List.of(confirmed));

        mockMvc.perform(get("/api/productions")
                        .param("status", "CONFIRMADO")
                        .param("from", "2026-01-01T00:00:00")
                        .param("to", "2026-01-31T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(3))
                .andExpect(jsonPath("$[0].name").value("Feria de arte"))
                .andExpect(jsonPath("$[0].status").value("CONFIRMADO"));
    }

    // ------------------------------------------------------------------
    // Test 9 – GET /api/productions?from=...&to=... sin status
    // ------------------------------------------------------------------

    @Test
    void getAll_withDateRangeOnly_returnsFilteredList() throws Exception {

        LocalDateTime now = LocalDateTime.now();
        ProductionResponseDTO sample = new ProductionResponseDTO(
                4L, "organizer-abc", "Workshop",
                LocalDateTime.of(2026, 1, 10, 9, 0),
                "Santiago", "SOLICITADO", now, now
        );
        given(service.getFilteredProductions(
                eq(null),
                eq(LocalDateTime.of(2026, 1, 1, 0, 0, 0)),
                eq(LocalDateTime.of(2026, 1, 31, 23, 59, 59))))
                .willReturn(List.of(sample));

        mockMvc.perform(get("/api/productions")
                        .param("from", "2026-01-01T00:00:00")
                        .param("to", "2026-01-31T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(4))
                .andExpect(jsonPath("$[0].name").value("Workshop"));
    }

    // ------------------------------------------------------------------
    // Test 10 – GET sin filtros sigue usando getAllProductions
    // ------------------------------------------------------------------

    @Test
    void getAll_withoutFilters_delegatesToGetAllProductions() throws Exception {

        given(service.getAllProductions()).willReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/productions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(1));
    }

    // ------------------------------------------------------------------
    // Test 11 – POST válido → 201
    // ------------------------------------------------------------------

    @Test
    void create_validRequest_returns201() throws Exception {

        LocalDateTime now = LocalDateTime.now();
        ProductionResponseDTO created = new ProductionResponseDTO(
                5L, "organizer-abc", "Lanzamiento producto",
                LocalDateTime.of(2027, 3, 10, 19, 0),
                "Santiago", "SOLICITADO", now, now
        );
        given(service.createProduction(any())).willReturn(created);

        String body = objectMapper.writeValueAsString(
                new java.util.LinkedHashMap<>() {{
                    put("organizerId", "organizer-abc");
                    put("name", "Lanzamiento producto");
                    put("scheduledAt", "2027-03-10T19:00:00");
                    put("location", "Santiago");
                }});

        mockMvc.perform(post("/api/productions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.name").value("Lanzamiento producto"))
                .andExpect(jsonPath("$.status").value("SOLICITADO"));
    }

    // ------------------------------------------------------------------
    // Test 12 – PUT con status null → 400
    // ------------------------------------------------------------------

    @Test
    void updateStatus_nullStatus_returns400() throws Exception {

        mockMvc.perform(put("/api/productions/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    // ------------------------------------------------------------------
    // Test 12b – PUT con status inválido (String no mappeable) → 400
    // ------------------------------------------------------------------

    @Test
    void updateStatus_invalidStatusString_returns400() throws Exception {

        mockMvc.perform(put("/api/productions/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"NO_EXISTE_TIPO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    // ------------------------------------------------------------------
    // Test 13 – POST con nombre excediendo @Size → 400
    // ------------------------------------------------------------------

    @Test
    void create_nameTooLong_returns400() throws Exception {

        String longName = "A".repeat(151);
        String body = objectMapper.writeValueAsString(
                new java.util.LinkedHashMap<>() {{
                    put("organizerId", "organizer-abc");
                    put("name", longName);
                    put("scheduledAt", "2027-03-10T19:00:00");
                    put("location", "Santiago");
                }});

        mockMvc.perform(post("/api/productions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").isString());
    }

    // ------------------------------------------------------------------
    // Test 14 – Path variable no numérico → 400 (MethodArgumentTypeMismatchException)
    // ------------------------------------------------------------------

    @Test
    void getById_invalidIdType_returns400() throws Exception {
        mockMvc.perform(get("/api/productions/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Invalid parameter type: id"));
    }

    // ------------------------------------------------------------------
    // Test 15 – Query param date con formato inválido → 400
    // ------------------------------------------------------------------

    @Test
    void getAll_invalidDateFilter_returns400() throws Exception {
        mockMvc.perform(get("/api/productions")
                        .param("from", "fecha-invalida"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Invalid parameter type: from"));
    }

    // ------------------------------------------------------------------
    // Test 16 – Unsupported Media Type → 415
    // ------------------------------------------------------------------

    @Test
    void create_unsupportedMediaType_returns415() throws Exception {
        mockMvc.perform(post("/api/productions")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("texto plano"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.error").value("Unsupported Media Type"))
                .andExpect(jsonPath("$.message").value("Media type not supported. Please use application/json"));
    }
    @Test
    void unsupportedMethodReturns405() throws Exception {
        mockMvc.perform(delete("/api/productions"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/productions").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void unexpectedErrorDoesNotExposeInternalDetails() throws Exception {
        given(service.getAllProductions()).willThrow(new IllegalStateException("internal-diagnostic-marker"));
        mockMvc.perform(get("/api/productions"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
