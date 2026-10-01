package cl.duoc.eventomax.productions.messaging.rabbitmq;

import cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductionEmailCommandListenerTest {

    @Mock
    private RabbitMQCommandPublisher publisher;

    @InjectMocks
    private ProductionEmailCommandListener listener;

    private void fireEvent(ProductionStatus newStatus) {
        ProductionStatusChangedEvent event = new ProductionStatusChangedEvent(
                1L, "org-1", "Test Event", ProductionStatus.SOLICITADO, newStatus, LocalDateTime.now(), "Santiago", Instant.now()
        );
        listener.handleProductionStatusChanged(event);
    }

    @Test
    void whenStatusConfirmado_thenPublishEmail() {
        fireEvent(ProductionStatus.CONFIRMADO);
        verify(publisher, times(1)).publishEmailCommand(any(EmailProductionStatusPayload.class));
    }

    @Test
    void whenStatusEnMontaje_thenPublishEmail() {
        fireEvent(ProductionStatus.EN_MONTAJE);
        verify(publisher, times(1)).publishEmailCommand(any(EmailProductionStatusPayload.class));
    }

    @Test
    void whenStatusCerrado_thenPublishEmail() {
        fireEvent(ProductionStatus.CERRADO);
        verify(publisher, times(1)).publishEmailCommand(any(EmailProductionStatusPayload.class));
    }

    @Test
    void whenStatusEnEjecucion_thenDoNotPublishEmail() {
        fireEvent(ProductionStatus.EN_EJECUCION);
        verifyNoInteractions(publisher);
    }

    @Test
    void whenStatusCancelado_thenDoNotPublishEmail() {
        fireEvent(ProductionStatus.CANCELADO);
        verifyNoInteractions(publisher);
    }
}
