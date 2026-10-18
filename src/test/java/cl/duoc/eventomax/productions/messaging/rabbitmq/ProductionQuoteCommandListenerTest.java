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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ProductionQuoteCommandListenerTest {

    @Mock
    private RabbitMQCommandPublisher publisher;

    @InjectMocks
    private ProductionQuoteCommandListener listener;

    @Captor
    private ArgumentCaptor<QuoteCommandPayload> payloadCaptor;

    private ProductionStatusChangedEvent createEvent(ProductionStatus previousStatus, ProductionStatus newStatus) {
        return new ProductionStatusChangedEvent(
                20L,
                "org-ep2",
                "Festival Cierre EP2",
                previousStatus,
                newStatus,
                LocalDateTime.parse("2026-12-01T20:00:00"),
                "Movistar Arena",
                Instant.parse("2026-12-02T01:30:00Z")
        );
    }

    @Test
    void handleProductionStatusChanged_cerrado_publishesQuoteCommand() {
        ProductionStatusChangedEvent event = createEvent(ProductionStatus.EN_EJECUCION, ProductionStatus.CERRADO);

        listener.handleProductionStatusChanged(event);

        verify(publisher, times(1)).publishQuoteCommand(payloadCaptor.capture());

        QuoteCommandPayload payload = payloadCaptor.getValue();
        assertEquals(20L, payload.productionId());
        assertEquals("org-ep2", payload.organizerId());
        assertEquals("Festival Cierre EP2", payload.productionName());
        assertEquals("CERRADO", payload.status());
        assertEquals("2026-12-01T20:00", payload.scheduledAt());
        assertEquals("Movistar Arena", payload.location());
        assertEquals("2026-12-02T01:30:00Z", payload.closedAt());
    }

    @Test
    void handleProductionStatusChanged_cerradoWithoutOccurredAt_populatesClosedAtFallback() {
        ProductionStatusChangedEvent event = new ProductionStatusChangedEvent(
                21L,
                "org-ep2",
                "Festival Sin OccurredAt",
                ProductionStatus.EN_EJECUCION,
                ProductionStatus.CERRADO,
                LocalDateTime.parse("2026-12-01T20:00:00"),
                "Santiago",
                null
        );

        listener.handleProductionStatusChanged(event);

        verify(publisher, times(1)).publishQuoteCommand(payloadCaptor.capture());
        assertNotNull(payloadCaptor.getValue().closedAt());
    }

    @Test
    void handleProductionStatusChanged_confirmado_doesNotPublish() {
        listener.handleProductionStatusChanged(createEvent(ProductionStatus.SOLICITADO, ProductionStatus.CONFIRMADO));
        verifyNoInteractions(publisher);
    }

    @Test
    void handleProductionStatusChanged_enMontaje_doesNotPublish() {
        listener.handleProductionStatusChanged(createEvent(ProductionStatus.CONFIRMADO, ProductionStatus.EN_MONTAJE));
        verifyNoInteractions(publisher);
    }

    @Test
    void handleProductionStatusChanged_enEjecucion_doesNotPublish() {
        listener.handleProductionStatusChanged(createEvent(ProductionStatus.EN_MONTAJE, ProductionStatus.EN_EJECUCION));
        verifyNoInteractions(publisher);
    }

    @Test
    void handleProductionStatusChanged_cancelado_doesNotPublish() {
        listener.handleProductionStatusChanged(createEvent(ProductionStatus.SOLICITADO, ProductionStatus.CANCELADO));
        verifyNoInteractions(publisher);
    }
}

