package cl.duoc.eventomax.productions.messaging.rabbitmq;

import cl.duoc.eventomax.productions.messaging.common.MessageEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.io.File;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba de Integración E2E en Vivo (Fase B3):
 * Valida el circuito completo: Productions -> RabbitMQ (cmd.direct/quote.gen) -> Notify -> OpenPDF -> Archivo en .local-cert/b3
 */
public class QuoteCommandE2EPublishIT {

    private static final String HISTORICAL_CERT_HASH = "1FC65BC41E18F7D2B185A3610383FD66E8B6BACB736A9A3EAB7AB005C165F014";

    @Test
    @DisplayName("E2E B3: Publicación real en RabbitMQ, consumo por Notify, generación OpenPDF e idempotencia")
    void testEndToEndQuoteGenerationFlow() throws Exception {
        // 1. Leer credenciales del entorno local y verificar disponibilidad del broker en vivo B3
        File credsFile = new File("C:/Users/Nacho/Desktop/Cloud Native/.local-cert/credentials.json");
        Assumptions.assumeTrue(
                credsFile.exists() && isPortOpen("localhost", 25692),
                "Skipping live B3 E2E test when local credentials.json or live B3 RabbitMQ (localhost:25692) is not active");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode creds = mapper.readTree(credsFile);
        String rmqUser = creds.get("application").get("username").asText();
        String rmqPass = creds.get("application").get("password").asText();

        // 2. Configurar conexión real a RabbitMQ
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory("localhost", 25692);
        connectionFactory.setUsername(rmqUser);
        connectionFactory.setPassword(rmqPass);
        connectionFactory.setVirtualHost("/");

        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());

        // 3. Construir Payload Enriquecido Canónico para producción aislada de prueba #999
        Long testProdId = 999L;
        String eventId = "evt-b3-e2e-cert-999";
        String traceId = "b3-trace-e2e-20261020";
        String correlationId = "b3-corr-e2e-20261020";

        var services = List.of(
                new QuoteCommandPayload.ContractedServicePayload(1L, "EP2-DEMO Sonido Line Array", new BigDecimal("350.00"), "2026-10-20T01:05:00"),
                new QuoteCommandPayload.ContractedServicePayload(2L, "EP2-DEMO Iluminación Robótica", new BigDecimal("175.00"), "2026-10-20T01:06:00")
        );

        var timeline = List.of(
                new QuoteCommandPayload.TimelineItemPayload(1, null, "SOLICITADO", "2026-10-20T01:00:00.000Z", "user-admin", "evt-tl-1"),
                new QuoteCommandPayload.TimelineItemPayload(2, "SOLICITADO", "CONFIRMADO", "2026-10-20T01:05:00.000Z", "productor-1", "evt-tl-2"),
                new QuoteCommandPayload.TimelineItemPayload(3, "CONFIRMADO", "EN_EJECUCION", "2026-10-20T01:10:00.000Z", "productor-1", "evt-tl-3"),
                new QuoteCommandPayload.TimelineItemPayload(4, "EN_EJECUCION", "CERRADO", "2026-10-20T01:15:00.000Z", "admin-ep2", "evt-tl-4")
        );

        var payload = new QuoteCommandPayload(
                testProdId,
                "org-ep2-test-002",
                "EP2-DEMO Gala de Certificación Empresarial 2026",
                "CERRADO",
                "2026-11-25 20:00:00",
                "Centro de Convenciones Santiago",
                "2026-10-20T01:15:00Z",
                "2026-10-20T01:00:00Z",
                "co******@eventomax.cl",
                "Ignacio Valenzuela",
                "+56912345678",
                services,
                null,
                timeline,
                "kafka-evt-closing-999",
                "admin-ep2-oid"
        );

        var envelope = new MessageEnvelope<>(
                "GenerateClosingQuote",
                eventId,
                Instant.now().toString(),
                traceId,
                correlationId,
                payload
        );

        // 4. Publicar mensaje a cmd.direct con routing key quote.gen
        rabbitTemplate.convertAndSend("cmd.direct", "quote.gen", envelope);
        System.out.println("[E2E B3] Comando publicado exitosamente en cmd.direct / quote.gen con eventId=" + eventId);

        // 5. Esperar a que el consumidor real de Notify procese el mensaje y genere el PDF en .local-cert/b3
        Path b3Dir = Path.of("C:/Users/Nacho/Desktop/Cloud Native/.local-cert/b3");
        Path expectedPdf = b3Dir.resolve("quote-production-999-" + eventId + ".pdf");
        Path expectedLatestPdf = b3Dir.resolve("quote-production-999-latest.pdf");

        boolean pdfGenerated = false;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(1000);
            if (Files.exists(expectedPdf) && Files.size(expectedPdf) > 20000) {
                pdfGenerated = true;
                break;
            }
        }

        assertTrue(pdfGenerated, "El acta PDF empresarial debe haber sido generada en: " + expectedPdf);
        long pdfSize = Files.size(expectedPdf);
        System.out.println("[E2E B3] PDF empresarial generado exitosamente! Tamaño: " + pdfSize + " bytes en " + expectedPdf);
        assertTrue(pdfSize > 25000, "El PDF empresarial con fuentes Roboto y branding debe ser mayor a 25KB, actual: " + pdfSize);

        // Validar formato del archivo binario
        byte[] pdfBytes = Files.readAllBytes(expectedPdf);
        String header = new String(pdfBytes, 0, 8);
        assertTrue(header.startsWith("%PDF-1."), "Debe ser un archivo PDF válido, encabezado: " + header);

        // Calcular SHA-256 del PDF nuevo
        String newPdfHash = calculateSha256(expectedPdf);
        System.out.println("[E2E B3] SHA-256 del PDF empresarial generado: " + newPdfHash);
        assertNotNull(newPdfHash);

        // 6. Prueba de Idempotencia: Republicar el MISMO comando
        long lastModifiedBefore = Files.getLastModifiedTime(expectedPdf).toMillis();
        Thread.sleep(1000);
        rabbitTemplate.convertAndSend("cmd.direct", "quote.gen", envelope);
        System.out.println("[E2E B3] Comando duplicado republicado con mismo eventId=" + eventId);
        Thread.sleep(3000);

        long lastModifiedAfter = Files.getLastModifiedTime(expectedPdf).toMillis();
        assertEquals(lastModifiedBefore, lastModifiedAfter, "El archivo PDF no debe haber sido regenerado tras recibir comando duplicado");
        System.out.println("[E2E B3] Idempotencia verificada: comando duplicado reconocido y no regeneró el archivo.");

        // 7. Salvaguarda Absoluta de Evidencias Históricas PRO-06
        Path pro06Dir = Path.of("C:/Users/Nacho/Desktop/Cloud Native/.local-cert/quotes");
        Path prod1EventPdf = pro06Dir.resolve("quote-production-1-678c5962-6568-4db9-9e1a-4a8fb7a68614.pdf");
        Path prod1LatestPdf = pro06Dir.resolve("quote-production-1-latest.pdf");

        assertTrue(Files.exists(prod1EventPdf), "Evidencia histórica de evento PRO-06 debe existir");
        assertTrue(Files.exists(prod1LatestPdf), "Evidencia histórica latest PRO-06 debe existir");

        String hashEvent = calculateSha256(prod1EventPdf);
        String hashLatest = calculateSha256(prod1LatestPdf);

        assertEquals(HISTORICAL_CERT_HASH, hashEvent, "Hash del PDF histórico de evento PRO-06 debe estar intacto");
        assertEquals(HISTORICAL_CERT_HASH, hashLatest, "Hash del PDF histórico latest PRO-06 debe estar intacto");
        System.out.println("[E2E B3] Verificación PRO-06: Hashes SHA-256 100% inalterados (" + HISTORICAL_CERT_HASH + ")");

        connectionFactory.destroy();
    }

    private static boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String calculateSha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(path);
        byte[] hash = digest.digest(bytes);
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString().toUpperCase();
    }
}
