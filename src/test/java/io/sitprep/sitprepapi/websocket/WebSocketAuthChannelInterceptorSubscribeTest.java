package io.sitprep.sitprepapi.websocket;

import io.sitprep.sitprepapi.service.PlanActivationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SUBSCRIBE authorization on the per-activation acks topic (2026-07-07
 * hardening): the activationId rides in the share link, so the topic name is
 * guessable by any link holder — the subscribe itself must be authorized.
 * Pure Mockito, no Spring context.
 */
class WebSocketAuthChannelInterceptorSubscribeTest {

    private PlanActivationService service;
    private WebSocketAuthChannelInterceptor interceptor;
    private final MessageChannel channel = mock(MessageChannel.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = mock(PlanActivationService.class);
        ObjectProvider<PlanActivationService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(service);
        interceptor = new WebSocketAuthChannelInterceptor(provider);
    }

    private Message<byte[]> subscribe(String destination, String principalEmail) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        if (principalEmail != null) {
            accessor.setUser(new StompPrincipal(principalEmail));
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void acksTopic_allowsAuthorizedReader() {
        when(service.canReadActivationAcks("act-1", "owner@x.com")).thenReturn(true);

        assertNotNull(interceptor.preSend(
                subscribe("/topic/activations/act-1/acks", "owner@x.com"), channel));
        verify(service).canReadActivationAcks("act-1", "owner@x.com");
    }

    @Test
    void acksTopic_rejectsUnauthorizedIdentity() {
        when(service.canReadActivationAcks("act-1", "attacker@x.com")).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(
                subscribe("/topic/activations/act-1/acks", "attacker@x.com"), channel));
    }

    @Test
    void acksTopic_rejectsSessionWithNoIdentity() {
        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(
                subscribe("/topic/activations/act-1/acks", null), channel));
        verifyNoInteractions(service);
    }

    @Test
    void nonAcksTopics_keepLinkPossessionContract() {
        // Plan-update frames carry no PII; recipients (incl. guests once
        // anonymous WS ships) must keep receiving them without a reader gate.
        assertNotNull(interceptor.preSend(
                subscribe("/topic/activations/act-1/plan", null), channel));
        assertNotNull(interceptor.preSend(
                subscribe("/topic/notifications", null), channel));
        verifyNoInteractions(service);
    }
}
