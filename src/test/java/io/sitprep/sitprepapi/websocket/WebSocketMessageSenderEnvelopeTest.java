package io.sitprep.sitprepapi.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class WebSocketMessageSenderEnvelopeTest {

    @Test
    void sendsVersionedEnvelopeWithoutChangingLegacySenders() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        WebSocketMessageSender sender = new WebSocketMessageSender(template);
        Object data = new Object();

        sender.sendEvent("/topic/user/a@example.com/sync", "me.changed", data);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSend(
                org.mockito.ArgumentMatchers.eq("/topic/user/a@example.com/sync"),
                payload.capture());
        RealtimeEnvelope<?> envelope = (RealtimeEnvelope<?>) payload.getValue();
        assertEquals(RealtimeEnvelope.CURRENT_VERSION, envelope.v());
        assertEquals("me.changed", envelope.type());
        assertNotNull(envelope.at());
        assertEquals(data, envelope.data());
    }

    @Test
    void rejectsIncompleteEventBeforePublishing() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        WebSocketMessageSender sender = new WebSocketMessageSender(template);

        assertThrows(IllegalArgumentException.class,
                () -> sender.sendEvent(" ", "me.changed", new Object()));
        assertThrows(IllegalArgumentException.class,
                () -> sender.sendEvent("/topic/user/a@example.com/sync", " ", new Object()));
        assertThrows(IllegalArgumentException.class,
                () -> sender.sendEvent("/topic/user/a@example.com/sync", "me.changed", null));
        verifyNoInteractions(template);
    }
}
