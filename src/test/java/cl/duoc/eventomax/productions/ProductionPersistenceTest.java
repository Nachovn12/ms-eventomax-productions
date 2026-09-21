package cl.duoc.eventomax.productions;

import cl.duoc.eventomax.productions.model.Production;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.repository.ProductionRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class ProductionPersistenceTest {

    @Autowired
    private ProductionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatesAndJpaValidatesProductionSchema() {
        Map<String, Object> migration = jdbcTemplate.queryForMap(
                "select \"version\", \"script\", \"success\" from \"flyway_schema_history\" "
                        + "where \"version\" = '1'");
        assertThat(migration.get("SCRIPT")).isEqualTo("V1__create_productions_table.sql");
        assertThat(migration.get("SUCCESS")).isEqualTo(true);

        Set<String> columns = Set.copyOf(jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_name = 'PRODUCTIONS'", String.class));
        assertThat(columns).containsExactlyInAnyOrder(
                "ID", "ORGANIZER_ID", "NAME", "SCHEDULED_AT", "LOCATION", "STATUS", "CREATED_AT", "UPDATED_AT");
    }

    @Test
    void persistsProductionStatusAndTimestamps() {
        Production production = new Production(
                "organizer-123",
                "Evento corporativo",
                LocalDateTime.of(2026, 10, 1, 18, 0),
                "Santiago");

        Production saved = repository.saveAndFlush(production);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(ProductionStatus.SOLICITADO);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select organizer_id, name, scheduled_at, location, status, created_at, updated_at "
                        + "from productions where id = ?", saved.getId());
        assertThat(row.get("ORGANIZER_ID")).isEqualTo("organizer-123");
        assertThat(row.get("NAME")).isEqualTo("Evento corporativo");
        assertThat(row.get("LOCATION")).isEqualTo("Santiago");
        assertThat(row.get("STATUS")).isEqualTo("SOLICITADO");
        assertThat(row.get("CREATED_AT")).isNotNull();
        assertThat(row.get("UPDATED_AT")).isNotNull();
    }
}
