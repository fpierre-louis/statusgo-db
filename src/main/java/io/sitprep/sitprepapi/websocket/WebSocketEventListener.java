package io.sitprep.sitprepapi.websocket;

import io.sitprep.sitprepapi.service.WebSocketPresenceBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final WebSocketPresenceBroadcastService presenceBroadcastService;

    public WebSocketEventListener(WebSocketPresenceBroadcastService presenceBroadcastService) {
        this.presenceBroadcastService = presenceBroadcastService;
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();

        // MVP: allow optional “email” header (NOT secure; convenience only)
        String email = accessor.getFirstNativeHeader("email");
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
}
