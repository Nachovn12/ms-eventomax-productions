package cl.duoc.eventomax.productions.messaging.rabbitmq;

import cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Listener post-commit que publica el comando {@code GenerateClosingQuote} hacia
 * {@code cmd.direct} ({@code quote.gen} -> {@code q.cmd.quote}) cuando una producción
 * transiciona al estado {@code CERRADO}.
 */
@Component
public class ProductionQuoteCommandListener {

    private final RabbitMQCommandPublisher publisher;

    public ProductionQuoteCommandListener(RabbitMQCommandPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleProductionStatusChanged(ProductionStatusChangedEvent event) {
        if (event.newStatus() != ProductionStatus.CERRADO) {
            return;
        }

        String closedAt = event.occurredAt() != null
                ? event.occurredAt().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)
                : Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);

        QuoteCommandPayload payload = new QuoteCommandPayload(
                event.productionId(),
                event.organizerId(),
                event.productionName(),
                event.newStatus().name(),
                event.scheduledAt() != null ? event.scheduledAt().toString() : null,
                event.location(),
                closedAt
        );

        // AFTER_COMMIT garantiza que el comando solo se emita tras confirmar el estado CERRADO en PostgreSQL.
        publisher.publishQuoteCommand(payload);
    }
}

