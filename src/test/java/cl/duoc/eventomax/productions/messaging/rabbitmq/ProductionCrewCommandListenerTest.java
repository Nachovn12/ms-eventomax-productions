package cl.duoc.eventomax.productions.messaging.rabbitmq;

import cl.duoc.eventomax.productions.event.ProductionStatusChangedEvent;
import cl.duoc.eventomax.productions.model.ProductionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductionCrewCommandListenerTest {

    @Mock
    private RabbitMQCommandPublisher publisher;

    @InjectMocks
    private ProductionCrewCommandListener listener;

    @Captor
    private ArgumentCaptor<CrewTicketPayload> payloadCaptor;

    private ProductionStatusChangedEvent createEvent(ProductionStatus previousStatus, ProductionStatus newStatus) {
        return new ProductionStatusChangedEvent(
                10L,
                "org-1",
                "Evento Crew",
                previousStatus,
                newStatus,
                LocalDateTime.parse("2026-12-01T20:00:00"),
                "Santiago",
                Instant.now()
        );
    }

    private ProductionStatusChangedEvent createEvent(ProductionStatus status) {
        return createEvent(ProductionStatus.SOLICITADO, status);
    }

    @Test
    void handleProductionStatusChanged_enMontaje_publishesCrewCommand() {
        ProductionStatusChangedEvent event = createEvent(ProductionStatus.CONFIRMADO, ProductionStatus.EN_MONTAJE);

        listener.handleProductionStatusChanged(event);

        verify(publisher, times(1)).publishCrewTicketCommand(payloadCaptor.capture());

        CrewTicketPayload payload = payloadCaptor.getValue();
        assertEquals(10L, payload.productionId());
        assertEquals("Evento Crew", payload.productionName());
        assertEquals("2026-12-01T20:00", payload.scheduledAt());
        assertEquals("Santiago", payload.location());
        assertEquals("EN_MONTAJE", payload.status());
    }

    @Test
    void handleProductionStatusChanged_confirmado_doesNotPublish() {
        listener.handleProductionStatusChanged(createEvent(ProductionStatus.CONFIRMADO));
        verifyNoInteractions(publisher);
    }

    @Test
    void handleProductionStatusChanged_cerrado_doesNotPublish() {
        listener.handleProductionStatusChanged(createEvent(ProductionStatus.CERRADO));
        verifyNoInteractions(publisher);
    }

    @Test
    void handleProductionStatusChanged_enEjecucion_doesNotPublish() {
        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_EJECUCION));
        verifyNoInteractions(publisher);
    }

    @Test
    void handleProductionStatusChanged_cancelado_doesNotPublish() {
        listener.handleProductionStatusChanged(createEvent(ProductionStatus.CANCELADO));
        verifyNoInteractions(publisher);
    }

    @Test
    void handleProductionStatusChanged_solicitado_doesNotPublish() {
        listener.handleProductionStatusChanged(createEvent(ProductionStatus.SOLICITADO));
        verifyNoInteractions(publisher);
    }
}
