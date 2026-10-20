package cl.duoc.eventomax.productions.messaging.rabbitmq;

import cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ProductionCrewCommandListener {

    private final RabbitMQCommandPublisher publisher;

    public ProductionCrewCommandListener(RabbitMQCommandPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleProductionStatusChanged(ProductionStatusChangedEvent event) {
        if (event.newStatus() != ProductionStatus.EN_MONTAJE) {
            return;
        }

        CrewTicketPayload payload = new CrewTicketPayload(
                event.productionId(),
                event.productionName(),
                event.scheduledAt() != null ? event.scheduledAt().toString() : null,
                event.location(),
                event.newStatus().name()
        );

        // AFTER_COMMIT evita publicar antes del commit de PostgreSQL,
        // pero no garantiza atomicidad DB/broker; Outbox queda fuera
        // de este incremento.
        publisher.publishCrewTicketCommand(payload);
    }
}
