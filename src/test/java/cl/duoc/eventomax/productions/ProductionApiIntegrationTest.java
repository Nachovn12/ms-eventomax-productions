package cl.duoc.eventomax.productions;

import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductionApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProductionRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearProductions() {
        repository.deleteAll();
    }

    @Test
    void statusResponseMatchesPersistenceAndReadsDoNotChangeTimestamps() throws Exception {
        Production saved = save("Audit", LocalDateTime.of(2099, 6, 15, 18, 0), ProductionStatus.SOLICITADO);
        Long id = saved.getId();
        LocalDateTime createdAt = timestamp(id, "created_at");
        // A deterministic earlier value makes a stale response visible without sleeps.
        jdbc.update("update productions set updated_at = ? where id = ?",
                LocalDateTime.of(2000, 1, 1, 0, 0), id);

        String updated = mvc.perform(put("/api/productions/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CONFIRMADO\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        LocalDateTime persisted = timestamp(id, "updated_at");
        assertThat(persisted).isAfter(LocalDateTime.of(2000, 1, 1, 0, 0));
        assertThat(LocalDateTime.parse(JsonPath.read(updated, "$.updatedAt"))).isEqualTo(persisted);

        for (int i = 0; i < 2; i++) {
            String read = mvc.perform(get("/api/productions/{id}", id))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(LocalDateTime.parse(JsonPath.read(read, "$.updatedAt"))).isEqualTo(persisted);
        }
        mvc.perform(get("/api/productions")).andExpect(status().isOk());
        mvc.perform(get("/api/productions").param("status", "CONFIRMADO")).andExpect(status().isOk());
        assertThat(timestamp(id, "created_at")).isEqualTo(createdAt);
        assertThat(timestamp(id, "updated_at")).isEqualTo(persisted);
    }

    @ParameterizedTest
    @MethodSource("filterCases")
    void optionalFiltersMatchPersistedProductions(String state, String from, String to,
                                                 String[] expected) throws Exception {
        save("Early", LocalDateTime.of(2099, 1, 1, 0, 0), ProductionStatus.SOLICITADO);
        save("Middle", LocalDateTime.of(2099, 6, 1, 0, 0), ProductionStatus.CONFIRMADO);
        save("Late", LocalDateTime.of(2099, 12, 1, 0, 0), ProductionStatus.CONFIRMADO);
        var request = get("/api/productions");
        if (state != null) request.param("status", state);
        if (from != null) request.param("from", from);
        if (to != null) request.param("to", to);
        mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", containsInAnyOrder(expected)));
    }

    static Stream<Arguments> filterCases() {
        String boundary = "2099-06-01T00:00:00";
        return Stream.of(
                Arguments.of(null, null, null, new String[]{"Early", "Middle", "Late"}),
                Arguments.of("CONFIRMADO", null, null, new String[]{"Middle", "Late"}),
                Arguments.of(null, boundary, null, new String[]{"Middle", "Late"}),
                Arguments.of(null, null, boundary, new String[]{"Early", "Middle"}),
                Arguments.of(null, boundary, boundary, new String[]{"Middle"}),
                Arguments.of("CONFIRMADO", boundary, null, new String[]{"Middle", "Late"}),
                Arguments.of("CONFIRMADO", null, boundary, new String[]{"Middle"}),
                Arguments.of("CONFIRMADO", boundary, boundary, new String[]{"Middle"}));
    }

    @Test
    void reversedDateRangeReturns400() throws Exception {
        mvc.perform(get("/api/productions").param("from", "2099-12-01T00:00:00")
                        .param("to", "2099-01-01T00:00:00"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    private Production save(String name, LocalDateTime date, ProductionStatus status) {
        Production production = new Production("audit-organizer", name, date, "Audit location");
        production.setStatus(status);
        return repository.saveAndFlush(production);
    }

    private LocalDateTime timestamp(Long id, String column) {
        return jdbc.queryForObject("select " + column + " from productions where id = ?",
                LocalDateTime.class, id);
    }
}
