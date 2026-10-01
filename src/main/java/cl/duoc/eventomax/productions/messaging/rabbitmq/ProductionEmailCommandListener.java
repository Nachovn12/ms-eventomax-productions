package cl.duoc.eventomax.productions.messaging.rabbitmq;

import cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ProductionEmailCommandListener {

    private final RabbitMQCommandPublisher publisher;

    public ProductionEmailCommandListener(RabbitMQCommandPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleProductionStatusChanged(ProductionStatusChangedEvent event) {
        /*
         * AFTER_COMMIT evita publicar antes del commit de PostgreSQL, pero no garantiza atomicidad DB/broker; Outbox queda fuera de este incremento.
         */
        ProductionStatus status = event.newStatus();

        if (status == ProductionStatus.CONFIRMADO || status == ProductionStatus.EN_MONTAJE || status == ProductionStatus.CERRADO) {

            EmailProductionStatusPayload payload = new EmailProductionStatusPayload(
                    event.productionId(),
                    event.organizerId(),
                    event.productionName(),
                    status.name(),
                    event.scheduledAt() != null ? event.scheduledAt().toString() : null,
                    event.location()
            );

            publisher.publishEmailCommand(payload);
        }
    }
}
