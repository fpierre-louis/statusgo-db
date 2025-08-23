// src/main/java/io/sitprep/sitprepapi/websocket/WebSocketEventListener.java
package io.sitprep.sitprepapi.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

/**
 * Listens for WebSocket connection lifecycle events to manage per-user presence.
 * Uses session-aware methods in WebSocketPresenceService (addSession/removeSession).
 */
@Component
public class WebSocketEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final WebSocketPresenceService presenceService;

    public WebSocketEventListener(WebSocketPresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor sha = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = sha.getSessionId();
        Principal principal = sha.getUser();

        if (principal == null || principal.getName() == null) {
            log.warn("⚠️ WebSocket CONNECTED but Principal is null (sessionId={}).", sessionId);
            return;
        }
        if (sessionId == null) {
            log.warn("⚠️ WebSocket CONNECTED but sessionId is null for user {}.", principal.getName());
            return;
        }

        String email = principal.getName();
        presenceService.addSession(email, sessionId);
        log.info("✅ WebSocket CONNECTED: user={} sessionId={} (now online? {}).",
                email, sessionId, presenceService.isUserOnline(email));
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor sha = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = sha.getSessionId();

        // On disconnect, Spring sometimes provides Principal on the event itself.
        Principal principal = event.getUser();
        if (principal == null) {
            principal = sha.getUser();
        }

        if (principal == null || principal.getName() == null) {
            log.warn("⚠️ WebSocket DISCONNECTED but Principal is null (sessionId={}).", sessionId);
            return;
        }
        if (sessionId == null) {
            log.warn("⚠️ WebSocket DISCONNECTED but sessionId is null for user {}.", principal.getName());
            return;
        }

        String email = principal.getName();
        presenceService.removeSession(email, sessionId);
        log.info("❌ WebSocket DISCONNECTED: user={} sessionId={} (still online? {}).",
                email, sessionId, presenceService.isUserOnline(email));
    }
}
