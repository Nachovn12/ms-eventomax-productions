package cl.duoc.eventomax.productions;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

public class FlywayV5MigrationCompatibilityTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"));

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    @DisplayName("Compatibilidad Flyway V5: tareas preexistentes con attempt_count > 1 conservan alineación de generación con Catalog")
    void testFlywayV5Migration_withPreExistingTasks_preservesGenerationAlignment() throws Exception {
        String jdbcUrl = POSTGRES.getJdbcUrl();
        String username = POSTGRES.getUsername();
        String password = POSTGRES.getPassword();

        // 1. Ejecutar migraciones V1 a V4 exclusivamente (estado previo a V5)
        Flyway flywayV4 = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .target("4")
                .load();
        flywayV4.clean();
        flywayV4.migrate();

        // 2. Insertar producciones y tareas preexistentes simulando estado real antes de V5
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO productions (id, organizer_id, name, scheduled_at, location, status, created_at, updated_at) "
                        + "VALUES (101, 'org-1', 'Prod Normal', NOW(), 'Loc 1', 'SOLICITADO', NOW(), NOW()), "
                        + "       (102, 'org-2', 'Prod Reintentada', NOW(), 'Loc 2', 'CONFIRMADO', NOW(), NOW()), "
                        + "       (103, 'org-3', 'Prod MultiClaim', NOW(), 'Loc 3', 'SOLICITADO', NOW(), NOW()), "
                        + "       (104, 'org-4', 'Prod Initial', NOW(), 'Loc 4', 'SOLICITADO', NOW(), NOW())");

                stmt.execute("INSERT INTO inventory_reservation_tasks (id, production_id, status, items_json, attempt_count, created_at, updated_at) "
                        + "VALUES (1, 101, 'PROCESSING', '[{\"equipmentId\":1,\"quantity\":2}]', 1, NOW(), NOW()), "
                        + "       (2, 102, 'CONFIRMED', '[{\"equipmentId\":2,\"quantity\":5}]', 3, NOW(), NOW()), "
                        + "       (3, 103, 'PROCESSING', '[{\"equipmentId\":3,\"quantity\":10}]', 4, NOW(), NOW()), "
                        + "       (4, 104, 'PENDING', '[]', 0, NOW(), NOW())");
            }
        }

        // 3. Ejecutar migración V5 (la nueva migración que introduce reservation_generation)
        Flyway flywayV5 = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .target("5")
                .load();
        flywayV5.migrate();

        // 4. Verificar integridad y alineación de reservation_generation
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, production_id, attempt_count, reservation_generation FROM inventory_reservation_tasks ORDER BY id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    // Task 1: attempt_count = 1 -> reservation_generation debe ser 1
                    assertTrue(rs.next());
                    assertEquals(101L, rs.getLong("production_id"));
                    assertEquals(1, rs.getInt("attempt_count"));
                    assertEquals(1L, rs.getLong("reservation_generation"), "Task 1 (attempt_count 1) debe tener generation 1");

                    // Task 2: attempt_count = 3 (reserva activa en Catalog realizada en generación 3)
                    assertTrue(rs.next());
                    assertEquals(102L, rs.getLong("production_id"));
                    assertEquals(3, rs.getInt("attempt_count"));
                    assertEquals(3L, rs.getLong("reservation_generation"),
                            "DEFECTO DETECTADO: Task 2 preexistente con attempt_count=3 debe preservar generation=3 para no ser rechazada por Catalog en compensaciones");

                    // Task 3: attempt_count = 4 -> reservation_generation debe ser 4
                    assertTrue(rs.next());
                    assertEquals(103L, rs.getLong("production_id"));
                    assertEquals(4, rs.getInt("attempt_count"));
                    assertEquals(4L, rs.getLong("reservation_generation"), "Task 3 preexistente debe tener generation 4");

                    // Task 4: attempt_count = 0 -> reservation_generation debe ser mínimo 1
                    assertTrue(rs.next());
                    assertEquals(104L, rs.getLong("production_id"));
                    assertEquals(0, rs.getInt("attempt_count"));
                    assertEquals(1L, rs.getLong("reservation_generation"), "Task 4 con attempt 0 debe tener generation inicial 1");

                    assertFalse(rs.next());
                }
            }

            // 5. Simular la validación en Catalog para la tarea 102 (reserva activa en generación 3)
            // Catalog almacena: production_id = 102, generation = 3, closed = false
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS catalog_reservation_generations ("
                        + "production_id BIGINT PRIMARY KEY, generation BIGINT NOT NULL, closed BOOLEAN NOT NULL)");
                stmt.execute("INSERT INTO catalog_reservation_generations (production_id, generation, closed) "
                        + "VALUES (102, 3, false)");
            }

            // Consultar la generación migrada en Productions para la tarea 102
            long migratedGen102;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT reservation_generation FROM inventory_reservation_tasks WHERE production_id = 102")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    migratedGen102 = rs.getLong(1);
                }
            }

            // Comprobar la lógica de Catalog: checkGeneration(102, migratedGen102, true)
            // En Catalog: if (generation < current) return false;
            //             if (generation == current) { if (compensate) update closed = true; return true; }
            boolean catalogCompensationAccepted;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT generation, closed FROM catalog_reservation_generations WHERE production_id = 102")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    long catalogCurrentGen = rs.getLong("generation");
                    boolean catalogClosed = rs.getBoolean("closed");

                    if (migratedGen102 < catalogCurrentGen) {
                        catalogCompensationAccepted = false;
                    } else if (migratedGen102 == catalogCurrentGen) {
                        catalogCompensationAccepted = !catalogClosed;
                        if (catalogCompensationAccepted) {
                            try (Statement stmtUpdate = conn.createStatement()) {
                                stmtUpdate.execute("UPDATE catalog_reservation_generations SET closed = true WHERE production_id = 102");
                            }
                        }
                    } else {
                        catalogCompensationAccepted = false;
                    }
                }
            }

            assertTrue(catalogCompensationAccepted,
                    "Catalog debe aceptar la compensación post-migración V5 para la producción 102 porque generation coincide con catalog (3)");

            // Confirmar que la reserva en Catalog quedó cerrada exitosamente
            try (Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT closed FROM catalog_reservation_generations WHERE production_id = 102")) {
                    assertTrue(rs.next());
                    assertTrue(rs.getBoolean("closed"), "La reserva en Catalog quedó cerrada y el stock liberado sin pérdida");
                }
            }
        }
    }
}

