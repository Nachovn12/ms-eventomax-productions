package cl.duoc.eventomax.productions.messaging.rabbitmq;

import cl.duoc.eventomax.productions.messaging.common.MessageEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publicador de comandos operativos hacia RabbitMQ ({@code cmd.direct}).
 * <p>
 * <b>Semántica de entrega post-commit y Publisher Confirms:</b>
 * 1. Los listeners que invocan este componente se ejecutan en {@code TransactionPhase.AFTER_COMMIT}
 *    para garantizar que ningún comando sea emitido a RabbitMQ si la transacción de PostgreSQL hace rollback.
 * 2. Soporta Publisher Confirms correlacionados ({@code CorrelationData}): comprueba ACK del broker,
 *    NACK del broker, timeouts configurables y mensajes no enrutables ({@code ReturnedMessage}).
 * 3. Registra errores estructurados con {@code eventId}, {@code productionId}, {@code traceId}
 *    y {@code correlationId}.
 */
@Component
public class RabbitMQCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQCommandPublisher.class);
    private static final String DEFAULT_QUOTE_ROUTING_KEY = "quote.gen";
    private static final long DEFAULT_CONFIRM_TIMEOUT_MS = 5000L;

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String emailRoutingKey;
    private final String crewRoutingKey;
    private final String quoteRoutingKey;
    private long confirmTimeoutMs;

    public RabbitMQCommandPublisher(
            RabbitTemplate rabbitTemplate,
            String exchange,
            String emailRoutingKey,
            String crewRoutingKey) {
        this(rabbitTemplate, exchange, emailRoutingKey, crewRoutingKey, DEFAULT_QUOTE_ROUTING_KEY, DEFAULT_CONFIRM_TIMEOUT_MS);
    }

    public RabbitMQCommandPublisher(
            RabbitTemplate rabbitTemplate,
            String exchange,
            String emailRoutingKey,
            String crewRoutingKey,
            String quoteRoutingKey) {
        this(rabbitTemplate, exchange, emailRoutingKey, crewRoutingKey, quoteRoutingKey, DEFAULT_CONFIRM_TIMEOUT_MS);
    }

    @Autowired
    public RabbitMQCommandPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${eventomax.messaging.rabbitmq.exchange}") String exchange,
            @Value("${eventomax.messaging.rabbitmq.routing-key.email}") String emailRoutingKey,
            @Value("${eventomax.messaging.rabbitmq.routing-key.crew}") String crewRoutingKey,
            @Value("${eventomax.messaging.rabbitmq.routing-key.quote:quote.gen}") String quoteRoutingKey,
            @Value("${eventomax.messaging.rabbitmq.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.emailRoutingKey = emailRoutingKey;
        this.crewRoutingKey = crewRoutingKey;
        this.quoteRoutingKey = quoteRoutingKey;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    public long getConfirmTimeoutMs() {
        return confirmTimeoutMs;
    }

    public void setConfirmTimeoutMs(long confirmTimeoutMs) {
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    public boolean publishEmailCommand(EmailProductionStatusPayload payload) {
        String eventId = UUID.randomUUID().toString();
        Long prodId = payload != null ? payload.productionId() : null;

        MessageEnvelope<EmailProductionStatusPayload> envelope = new MessageEnvelope<>(
                "SendProductionStatusEmail",
                eventId,
                getTimestamp(),
                resolveTraceId(),
                resolveCorrelationId(),
                payload
        );

        CorrelationData correlationData = new CorrelationData(eventId);

        try {
            rabbitTemplate.convertAndSend(exchange, emailRoutingKey, envelope, message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            }, correlationData);

            return handleConfirm(correlationData, eventId, prodId, "Email Command");
        } catch (AmqpException e) {
            log.error("Failed to publish email command for production {} with eventId {}: {}",
                    prodId, eventId, e.getMessage(), e);
            return false;
        }
    }

    public boolean publishCrewTicketCommand(CrewTicketPayload payload) {
        String eventId = UUID.randomUUID().toString();
        Long prodId = payload != null ? payload.productionId() : null;

        MessageEnvelope<CrewTicketPayload> envelope = new MessageEnvelope<>(
                "GenerateCrewTicket",
                eventId,
                getTimestamp(),
                resolveTraceId(),
                resolveCorrelationId(),
                payload
        );

        CorrelationData correlationData = new CorrelationData(eventId);

        try {
            rabbitTemplate.convertAndSend(exchange, crewRoutingKey, envelope, message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            }, correlationData);

            return handleConfirm(correlationData, eventId, prodId, "Crew Ticket Command");
        } catch (AmqpException e) {
            log.error("Failed to publish crew ticket command for production {} with eventId {}: {}",
                    prodId, eventId, e.getMessage(), e);
            return false;
        }
    }

    public boolean publishQuoteCommand(QuoteCommandPayload payload) {
        String eventId = (payload != null && payload.kafkaClosingEventId() != null && !payload.kafkaClosingEventId().isBlank())
                ? payload.kafkaClosingEventId()
                : UUID.randomUUID().toString();
        return publishQuoteCommand(payload, eventId);
    }

    public boolean publishQuoteCommand(QuoteCommandPayload payload, String eventId) {
        String actualEventId = (eventId != null && !eventId.isBlank()) ? eventId : UUID.randomUUID().toString();
        Long prodId = payload != null ? payload.productionId() : null;

        MessageEnvelope<QuoteCommandPayload> envelope = new MessageEnvelope<>(
                "GenerateClosingQuote",
                actualEventId,
                getTimestamp(),
                resolveTraceId(),
                resolveCorrelationId(),
                payload
        );

        CorrelationData correlationData = new CorrelationData(actualEventId);

        try {
            rabbitTemplate.convertAndSend(exchange, quoteRoutingKey, envelope, message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            }, correlationData);

            return handleConfirm(correlationData, actualEventId, prodId, "Closing Quote Command");
        } catch (AmqpException e) {
            log.error("Failed to publish closing quote command for production {} with eventId {}: {}",
                    prodId, actualEventId, e.getMessage(), e);
            return false;
        }
    }

    private boolean handleConfirm(CorrelationData correlationData, String eventId, Long productionId, String commandType) {
        if (correlationData == null) {
            return true;
        }

        CompletableFuture<CorrelationData.Confirm> future = correlationData.getFuture();
        if (future == null) {
            log.warn("Publisher confirms no activos en CorrelationData para {} (productionId={}, eventId={})",
                    commandType, productionId, eventId);
            return true;
        }

        try {
            CorrelationData.Confirm confirm = future.get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck()) {
                String reason = confirm != null ? confirm.getReason() : "No confirm object returned";
                log.error("Broker NACK recibido para {} (productionId={}, eventId={}): {}",
                        commandType, productionId, eventId, reason);
                return false;
            }

            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                log.error("Mensaje no enrutable devuelto por broker para {} (productionId={}, eventId={}): replyCode={}, replyText={}, exchange={}, routingKey={}",
                        commandType, productionId, eventId, returned.getReplyCode(), returned.getReplyText(), returned.getExchange(), returned.getRoutingKey());
                return false;
            }

            log.info("Published {} confirmed (ACK) by broker for production {} with eventId {}",
                    commandType, productionId, eventId);
            return true;
        } catch (TimeoutException te) {
            log.error("Timeout ({} ms) esperando Publisher Confirm para {} (productionId={}, eventId={})",
                    confirmTimeoutMs, commandType, productionId, eventId);
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("Interrupción esperando Publisher Confirm para {} (productionId={}, eventId={})",
                    commandType, productionId, eventId);
            return false;
        } catch (ExecutionException ee) {
            log.error("Error esperando Publisher Confirm para {} (productionId={}, eventId={}): {}",
                    commandType, productionId, eventId, ee.getMessage());
            return false;
        }
    }

    private String getTimestamp() {
        return Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
    }

    private String resolveTraceId() {
        String traceId = MDC.get("traceId");
        return (traceId == null || traceId.isBlank()) ? UUID.randomUUID().toString() : traceId;
    }

    private String resolveCorrelationId() {
        String correlationId = MDC.get("correlationId");
        return (correlationId == null || correlationId.isBlank()) ? UUID.randomUUID().toString() : correlationId;
    }
}
