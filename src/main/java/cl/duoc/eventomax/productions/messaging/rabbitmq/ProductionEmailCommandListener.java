package cl.duoc.eventomax.productions.messaging.rabbitmq;

import cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import cl.duoc.eventomax.productions.service.OrganizerContactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

@Component
public class ProductionEmailCommandListener {

    private static final Logger log = LoggerFactory.getLogger(ProductionEmailCommandListener.class);

    private final RabbitMQCommandPublisher publisher;
    private final OrganizerContactService organizerContactService;

    public ProductionEmailCommandListener(RabbitMQCommandPublisher publisher,
                                          OrganizerContactService organizerContactService) {
        this.publisher = publisher;
        this.organizerContactService = organizerContactService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleProductionStatusChanged(ProductionStatusChangedEvent event) {
        ProductionStatus status = event.newStatus();

        if (status == ProductionStatus.CONFIRMADO || status == ProductionStatus.EN_MONTAJE || status == ProductionStatus.CERRADO) {
            String organizerId = event.organizerId();
            Optional<String> contactEmailOpt = organizerContactService.getVerifiedRecipientEmail(organizerId);

            String recipientEmail = contactEmailOpt.orElse(null);
            if (recipientEmail == null || recipientEmail.isBlank()) {
                log.warn("[EMAIL_COMMAND_NOTICE] No existe contacto autorizado registrado para organizerId='{}' en productionId={}. "
                                + "Se despacha comando con recipientEmail=null para registro observable y gestión de fallo en Notify.",
                        organizerId, event.productionId());
            } else {
                log.info("Contacto resuelto para organizerId='{}': recipientEmail='{}' (productionId={})",
                        organizerId, recipientEmail, event.productionId());
            }

            EmailProductionStatusPayload payload = new EmailProductionStatusPayload(
                    event.productionId(),
                    organizerId,
                    recipientEmail,
                    event.productionName(),
                    status.name(),
                    event.scheduledAt() != null ? event.scheduledAt().toString() : null,
                    event.location()
            );

            publisher.publishEmailCommand(payload);
        }
    }
}
