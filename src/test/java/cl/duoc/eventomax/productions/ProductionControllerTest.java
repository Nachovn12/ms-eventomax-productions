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
                .andExpect(status().isNotFound());
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
    // Test 5 – PATCH transición válida → 200
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

        mockMvc.perform(patch("/api/productions/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMADO"));
    }

    // ------------------------------------------------------------------
    // Test 6 – PATCH transición inválida → 422
    // ------------------------------------------------------------------

    @Test
    void updateStatus_invalidTransition_returns422() throws Exception {

        given(service.updateStatus(eq(1L), any()))
                .willThrow(new InvalidTransitionException(
                        "Transicion invalida: SOLICITADO -> EN_MONTAJE. "
                        + "Transiciones permitidas desde SOLICITADO: [CONFIRMADO, CANCELADO]"));

        String body = objectMapper.writeValueAsString(
                new ProductionStatusUpdateDTO(ProductionStatus.EN_MONTAJE));

        mockMvc.perform(patch("/api/productions/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("Unprocessable Entity"))
                .andExpect(jsonPath("$.message").isString());
    }
}

