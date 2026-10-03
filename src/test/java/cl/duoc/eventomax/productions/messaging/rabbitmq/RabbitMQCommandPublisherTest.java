package cl.duoc.eventomax.productions.messaging.rabbitmq;

import cl.duoc.eventomax.productions.messaging.common.MessageEnvelope;
import org.junit.jupiter.api.BeforeEach;
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
    private ArgumentCaptor<MessagePostProcessor> postProcessorCaptor;

    @BeforeEach
    void setUp() {
        publisher = new RabbitMQCommandPublisher(rabbitTemplate, "cmd.direct", "email.send", "crew.ticket");
    }

    @Test
    void publishEmailCommand_success() {
        EmailProductionStatusPayload payload = new EmailProductionStatusPayload(
                1L, "org-1", "Test", "CONFIRMADO", "2026-10-01T10:00", "Loc"
        );

        publisher.publishEmailCommand(payload);

        verify(rabbitTemplate, times(1)).convertAndSend(
                eq("cmd.direct"), eq("email.send"), envelopeCaptor.capture(), postProcessorCaptor.capture()
        );

        MessageEnvelope<EmailProductionStatusPayload> envelope = envelopeCaptor.getValue();
        assertEquals("SendProductionStatusEmail", envelope.type());
        assertNotNull(envelope.eventId());
        assertNotNull(envelope.timestamp());
        assertNotNull(envelope.traceId());
        assertNotNull(envelope.correlationId());
        assertEquals(payload, envelope.payload());

        MessagePostProcessor mpp = postProcessorCaptor.getValue();
        Message mockMessage = new Message(new byte[0]);
        Message processedMessage = mpp.postProcessMessage(mockMessage);
        assertEquals(MessageDeliveryMode.PERSISTENT, processedMessage.getMessageProperties().getDeliveryMode());
    }

    @Test
    void publishEmailCommand_handlesExceptionGracefully() {
        EmailProductionStatusPayload payload = new EmailProductionStatusPayload(
                1L, "org-1", "Test", "CONFIRMADO", "2026-10-01T10:00", "Loc"
        );

        doThrow(new AmqpException("Broker down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));

        assertDoesNotThrow(() -> publisher.publishEmailCommand(payload));
    }

    @Test
    void publishCrewTicketCommand_success() {
        CrewTicketPayload payload = new CrewTicketPayload(
                10L, "Evento Crew", "2026-12-01T20:00", "Santiago", "EN_MONTAJE"
        );

        publisher.publishCrewTicketCommand(payload);

        verify(rabbitTemplate, times(1)).convertAndSend(
                eq("cmd.direct"), eq("crew.ticket"), crewEnvelopeCaptor.capture(), postProcessorCaptor.capture()
        );

        MessageEnvelope<CrewTicketPayload> envelope = crewEnvelopeCaptor.getValue();
        assertEquals("GenerateCrewTicket", envelope.type());
        assertNotNull(envelope.eventId());
        assertNotNull(envelope.timestamp());
        assertNotNull(envelope.traceId());
        assertNotNull(envelope.correlationId());
        assertEquals(payload, envelope.payload());

        MessagePostProcessor mpp = postProcessorCaptor.getValue();
        Message mockMessage = new Message(new byte[0]);
        Message processedMessage = mpp.postProcessMessage(mockMessage);
        assertEquals(MessageDeliveryMode.PERSISTENT, processedMessage.getMessageProperties().getDeliveryMode());
    }

    @Test
    void publishCrewTicketCommand_handlesExceptionGracefully() {
        CrewTicketPayload payload = new CrewTicketPayload(
                10L, "Evento Crew", "2026-12-01T20:00", "Santiago", "EN_MONTAJE"
        );

        doThrow(new AmqpException("Broker down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));

        assertDoesNotThrow(() -> publisher.publishCrewTicketCommand(payload));
    }
}
