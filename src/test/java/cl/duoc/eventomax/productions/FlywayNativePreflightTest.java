package cl.duoc.eventomax.productions;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

public class FlywayNativePreflightTest {

    @Test
    @DisplayName("Preflight 1: Flyway nativo aplica V1..V7 en base descartable con user_oid e indices")
    void testFlywayV7OnDisposable() throws Exception {
        File pwFile = new File("C:/Users/Nacho/Desktop/Cloud Native/.local-cert/postgres_password.txt");
        if (pwFile.exists() && isLocalPostgresReachable()) {
            String password = Files.readString(pwFile.toPath()).trim();
            String jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/eventomax_test_v7_disposable";
            verifyFlywayV7(jdbcUrl, "postgres", password);
            return;
        }

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))) {
            postgres.start();
            verifyFlywayV7(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }

    private void verifyFlywayV7(String jdbcUrl, String username, String password) throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load();

        MigrateResult result = flyway.migrate();
        assertTrue(result.success);
        String currentVersion = flyway.info().current() != null ? flyway.info().current().getVersion().getVersion() : null;
        assertEquals("7", currentVersion != null ? currentVersion : result.targetSchemaVersion);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {

            // Verificar tabla organizer_contacts y columna user_oid
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_name = 'organizer_contacts' AND column_name = 'user_oid'")) {
                assertTrue(rs.next(), "Columna user_oid debe existir en organizer_contacts");
                assertEquals("character varying", rs.getString("data_type"));
            }

            // Verificar indice idx_organizer_contacts_user_oid
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'organizer_contacts' AND indexname = 'idx_organizer_contacts_user_oid'")) {
                assertTrue(rs.next(), "Indice idx_organizer_contacts_user_oid debe existir");
            }

            // Verificar flyway_schema_history version 7 y checksum entero CRC32
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT version, description, success, checksum FROM flyway_schema_history WHERE version = '7'")) {
                assertTrue(rs.next());
                assertEquals("create organizer contacts table", rs.getString("description"));
                assertTrue(rs.getBoolean("success"));
                assertNotNull(rs.getObject("checksum"), "Checksum CRC32 debe estar registrado");
            }
        }
    }

    private static boolean isLocalPostgresReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5432), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
