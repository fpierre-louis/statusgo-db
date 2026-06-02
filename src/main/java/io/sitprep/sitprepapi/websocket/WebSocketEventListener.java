package io.sitprep.sitprepapi.websocket;

import io.sitprep.sitprepapi.service.GroupPostThreadPresenceService;
import io.sitprep.sitprepapi.service.WebSocketPresenceBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.security.Principal;

@Component
public class WebSocketEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final WebSocketPresenceBroadcastService presenceBroadcastService;
    private final WebSocketPresenceService presenceService;
    private final GroupPostThreadPresenceService threadPresenceService;

    public WebSocketEventListener(
            WebSocketPresenceBroadcastService presenceBroadcastService,
            WebSocketPresenceService presenceService,
            GroupPostThreadPresenceService threadPresenceService
    ) {
        this.presenceBroadcastService = presenceBroadcastService;
        this.presenceService = presenceService;
        this.threadPresenceService = threadPresenceService;
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();

        String email = principalEmail(accessor.getUser());
        presenceBroadcastService.addSession(sessionId, email);

        log.info("WS CONNECT sessionId={}, email={}", sessionId, email);
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();

        presenceBroadcastService.removeSession(sessionId);
        log.info("WS DISCONNECT sessionId={}", sessionId);
    }

    @EventListener
    public void handleWebSocketSubscribeListener(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        String destination = accessor.getDestination();
        String email = principalEmail(accessor.getUser());
        if (email == null) email = presenceService.getEmailForSession(sessionId);
        threadPresenceService.addSubscription(sessionId, subscriptionId, email, destination);
    }

    @EventListener
    public void handleWebSocketUnsubscribeListener(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        threadPresenceService.removeSubscription(accessor.getSessionId(), accessor.getSubscriptionId());
    }

    private static String principalEmail(Principal principal) {
        String name = principal == null ? null : principal.getName();
        return name == null || name.isBlank() ? null : name.trim().toLowerCase();
    }
}
