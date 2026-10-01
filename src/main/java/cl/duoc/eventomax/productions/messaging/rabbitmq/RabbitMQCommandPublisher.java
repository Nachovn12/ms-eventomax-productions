package cl.duoc.eventomax.productions.messaging.rabbitmq;

import cl.duoc.eventomax.productions.messaging.common.MessageEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class RabbitMQCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQCommandPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String emailRoutingKey;

    public RabbitMQCommandPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${eventomax.messaging.rabbitmq.exchange}") String exchange,
            @Value("${eventomax.messaging.rabbitmq.routing-key.email}") String emailRoutingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.emailRoutingKey = emailRoutingKey;
    }

    public void publishEmailCommand(EmailProductionStatusPayload payload) {
        String eventId = UUID.randomUUID().toString();
        String timestamp = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);

        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        String correlationId = MDC.get("correlationId");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MessageEnvelope<EmailProductionStatusPayload> envelope = new MessageEnvelope<>(
                "SendProductionStatusEmail",
                eventId,
                timestamp,
                traceId,
                correlationId,
                payload
        );

        try {
            rabbitTemplate.convertAndSend(exchange, emailRoutingKey, envelope, message -> {
                message.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
                return message;
            });
            log.info("Published Email Command for production {} with eventId {}", payload.productionId(), eventId);
        } catch (AmqpException e) {
            log.error(
                "Failed to publish email command for production {} with eventId {}",
                payload.productionId(),
                eventId,
                e
            );
        }
    }
}
