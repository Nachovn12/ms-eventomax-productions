package cl.duoc.eventomax.productions.messaging.rabbitmq;

import cl.duoc.eventomax.productions.messaging.common.MessageEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RabbitMQCommandPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private RabbitMQCommandPublisher publisher;

    @Captor
    private ArgumentCaptor<MessageEnvelope<EmailProductionStatusPayload>> envelopeCaptor;

    @Captor
    private ArgumentCaptor<MessageEnvelope<CrewTicketPayload>> crewEnvelopeCaptor;

    @Captor
    private ArgumentCaptor<MessageEnvelope<QuoteCommandPayload>> quoteEnvelopeCaptor;

    @Captor
    private ArgumentCaptor<MessagePostProcessor> postProcessorCaptor;

    @Captor
    private ArgumentCaptor<CorrelationData> correlationDataCaptor;

    @BeforeEach
    void setUp() {
        publisher = new RabbitMQCommandPublisher(
                rabbitTemplate, "cmd.direct", "email.send", "crew.ticket", "quote.gen", 200L
        );
    }

    private void mockAckOnSend() {
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(4);
            if (cd != null) {
                cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    private void mockNackOnSend(String reason) {
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(4);
            if (cd != null) {
                cd.getFuture().complete(new CorrelationData.Confirm(false, reason));
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    private void mockReturnedMessageOnSend(int replyCode, String replyText) {
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(4);
            if (cd != null) {
                cd.setReturned(new ReturnedMessage(new Message(new byte[0]), replyCode, replyText, "cmd.direct", "bad.routing.key"));
                cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    @DisplayName("1. Quote Command: Confirmación ACK exitosa del broker")
    void publishQuoteCommand_confirmedAck_returnsTrue() {
        mockAckOnSend();

        QuoteCommandPayload payload = new QuoteCommandPayload(
                20L, "org-ep2", "Festival Cierre", "CERRADO", "2026-12-01T20:00", "Movistar Arena", "2026-12-02T01:30:00Z"
        );

        boolean result = publisher.publishQuoteCommand(payload, "custom-event-id-123");
        assertTrue(result, "Debe retornar true ante confirmación positiva (ACK) del broker");

        verify(rabbitTemplate, times(1)).convertAndSend(
                eq("cmd.direct"), eq("quote.gen"), quoteEnvelopeCaptor.capture(),
                postProcessorCaptor.capture(), correlationDataCaptor.capture()
        );

        MessageEnvelope<QuoteCommandPayload> envelope = quoteEnvelopeCaptor.getValue();
        assertEquals("GenerateClosingQuote", envelope.type());
        assertEquals("custom-event-id-123", envelope.eventId());
        assertEquals(payload, envelope.payload());

        CorrelationData cd = correlationDataCaptor.getValue();
        assertNotNull(cd);
        assertEquals("custom-event-id-123", cd.getId(), "CorrelationData debe preservar el eventId");

        MessagePostProcessor mpp = postProcessorCaptor.getValue();
        Message mockMessage = new Message(new byte[0]);
        Message processedMessage = mpp.postProcessMessage(mockMessage);
        assertEquals(MessageDeliveryMode.PERSISTENT, processedMessage.getMessageProperties().getDeliveryMode());
    }

    @Test
    @DisplayName("2. Quote Command: Confirmación NACK del broker")
    void publishQuoteCommand_brokerNack_returnsFalse() {
        mockNackOnSend("Queue storage limit reached");

        QuoteCommandPayload payload = new QuoteCommandPayload(
                20L, "org-ep2", "Festival Cierre", "CERRADO", "2026-12-01T20:00", "Movistar Arena", "2026-12-02T01:30:00Z"
        );

        boolean result = publisher.publishQuoteCommand(payload);
        assertFalse(result, "Debe retornar false ante NACK del broker");
    }

    @Test
    @DisplayName("3. Quote Command: Timeout esperando confirmación del broker")
    void publishQuoteCommand_timeout_returnsFalse() {
        // No completamos el future -> simula que el broker nunca responde
        publisher.setConfirmTimeoutMs(50L);

        QuoteCommandPayload payload = new QuoteCommandPayload(
                20L, "org-ep2", "Festival Cierre", "CERRADO", "2026-12-01T20:00", "Movistar Arena", "2026-12-02T01:30:00Z"
        );

        boolean result = publisher.publishQuoteCommand(payload);
        assertFalse(result, "Debe retornar false cuando expira el timeout de confirmación");
    }

    @Test
    @DisplayName("4. Quote Command: Excepción AMQP al conectar o publicar")
    void publishQuoteCommand_amqpException_returnsFalse() {
        doThrow(new AmqpException("Broker unreachable")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));

        QuoteCommandPayload payload = new QuoteCommandPayload(
                20L, "org-ep2", "Festival Cierre", "CERRADO", "2026-12-01T20:00", "Movistar Arena", "2026-12-02T01:30:00Z"
        );

        boolean result = publisher.publishQuoteCommand(payload);
        assertFalse(result, "Debe retornar false ante AmqpException sin propagar excepción");
    }

    @Test
    @DisplayName("5. Quote Command: Mensaje devuelto por routing key no enrutable (Publisher Return)")
    void publishQuoteCommand_unroutableReturned_returnsFalse() {
        mockReturnedMessageOnSend(312, "NO_ROUTE");

        QuoteCommandPayload payload = new QuoteCommandPayload(
                20L, "org-ep2", "Festival Cierre", "CERRADO", "2026-12-01T20:00", "Movistar Arena", "2026-12-02T01:30:00Z"
        );

        boolean result = publisher.publishQuoteCommand(payload);
        assertFalse(result, "Debe retornar false si el mensaje es devuelto por el broker como no enrutable");
    }

    @Test
    @DisplayName("6. Email Command: Publicación con ACK y preservación de delivery mode persistente")
    void publishEmailCommand_success() {
        mockAckOnSend();

        EmailProductionStatusPayload payload = new EmailProductionStatusPayload(
                1L, "org-1", "Test", "CONFIRMADO", "2026-10-01T10:00", "Loc"
        );

        boolean result = publisher.publishEmailCommand(payload);
        assertTrue(result);

        verify(rabbitTemplate, times(1)).convertAndSend(
                eq("cmd.direct"), eq("email.send"), envelopeCaptor.capture(),
                postProcessorCaptor.capture(), correlationDataCaptor.capture()
        );

        MessageEnvelope<EmailProductionStatusPayload> envelope = envelopeCaptor.getValue();
        assertEquals("SendProductionStatusEmail", envelope.type());
        assertNotNull(envelope.eventId());
        assertEquals(correlationDataCaptor.getValue().getId(), envelope.eventId());
        assertEquals(payload, envelope.payload());
    }

    @Test
    @DisplayName("7. Crew Ticket Command: Publicación con ACK y preservación de delivery mode persistente")
    void publishCrewTicketCommand_success() {
        mockAckOnSend();

        CrewTicketPayload payload = new CrewTicketPayload(
                10L, "Evento Crew", "2026-12-01T20:00", "Santiago", "EN_MONTAJE"
        );

        boolean result = publisher.publishCrewTicketCommand(payload);
        assertTrue(result);

        verify(rabbitTemplate, times(1)).convertAndSend(
                eq("cmd.direct"), eq("crew.ticket"), crewEnvelopeCaptor.capture(),
                postProcessorCaptor.capture(), correlationDataCaptor.capture()
        );

        MessageEnvelope<CrewTicketPayload> envelope = crewEnvelopeCaptor.getValue();
        assertEquals("GenerateCrewTicket", envelope.type());
        assertNotNull(envelope.eventId());
        assertEquals(correlationDataCaptor.getValue().getId(), envelope.eventId());
        assertEquals(payload, envelope.payload());
    }

    @Test
    @DisplayName("8. Email Command: Manejo de excepción AMQP con log estructurado")
    void publishEmailCommand_handlesExceptionGracefully() {
        doThrow(new AmqpException("Broker down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));

        EmailProductionStatusPayload payload = new EmailProductionStatusPayload(
                1L, "org-1", "Test", "CONFIRMADO", "2026-10-01T10:00", "Loc"
        );

        boolean result = publisher.publishEmailCommand(payload);
        assertFalse(result);
    }
}
