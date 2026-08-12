package io.sitprep.sitprepapi.websocket;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.Principal;
import java.util.Map;

@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    /**
     * Every SUBSCRIBE rule lives in {@link WebSocketTopicAuthorizer}, which
     * is default-deny. This class stays about transport: authenticate the
     * CONNECT, resolve the session identity, delegate the decision.
     */
    private final WebSocketTopicAuthorizer topicAuthorizer;

    public WebSocketAuthChannelInterceptor(WebSocketTopicAuthorizer topicAuthorizer) {
        this.topicAuthorizer = topicAuthorizer;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            authenticateConnect(accessor);
            return message;
        }
        if (command == StompCommand.SUBSCRIBE) {
            authorizeSubscribe(accessor);
        }
        return message;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String token = extractToken(accessor);
        if (!StringUtils.hasText(token)) {
            throw new IllegalArgumentException("Authenticated WebSocket CONNECT required");
        }

        try {
            FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(token);
            String email = decoded.getEmail();
            if (!StringUtils.hasText(email)) {
                throw new IllegalArgumentException("Firebase token has no email");
            }
            String normalizedEmail = email.trim().toLowerCase();
            accessor.setUser(new StompPrincipal(normalizedEmail));
            if (accessor.getSessionAttributes() != null) {
                accessor.getSessionAttributes().put("email", normalizedEmail);
                accessor.getSessionAttributes().put("firebaseUid", decoded.getUid());
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid WebSocket auth token", e);
        }
    }

    /**
     * SUBSCRIBE authorization. The 2026-07-07 pass established the rationale
     * — CONNECT-only auth left every broker topic open to any authenticated
     * session — but applied it to a single destination. Every other topic
     * stayed open, including live household chat and group member
     * coordinates, and every topic added afterwards inherited that default.
     *
     * <p>The rule set now lives in {@link WebSocketTopicAuthorizer} and is
     * default-deny, so an unclassified destination fails closed instead of
     * silently streaming. See {@code docs/audits/post-by-id-authorization.md}.</p>
     */
    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (!StringUtils.hasText(destination)) {
            return;
        }
        if (!topicAuthorizer.canSubscribe(destination, sessionEmail(accessor))) {
            throw new IllegalArgumentException(
                    "Not authorized to subscribe to " + destination);
        }
    }

    /** Session identity set at CONNECT — principal first, session attribute fallback. */
    private static String sessionEmail(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user != null && StringUtils.hasText(user.getName())) {
            return user.getName();
        }
        Map<String, Object> attrs = accessor.getSessionAttributes();
        Object email = attrs != null ? attrs.get("email") : null;
        return (email instanceof String s && StringUtils.hasText(s)) ? s : null;
    }

    private static String extractToken(StompHeaderAccessor accessor) {
        String auth = accessor.getFirstNativeHeader("Authorization");
        if (StringUtils.hasText(auth) && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        String lowerAuth = accessor.getFirstNativeHeader("authorization");
        if (StringUtils.hasText(lowerAuth) && lowerAuth.startsWith("Bearer ")) {
            return lowerAuth.substring(7).trim();
        }
        String firebaseToken = accessor.getFirstNativeHeader("firebaseToken");
        if (StringUtils.hasText(firebaseToken)) return firebaseToken.trim();
        String accessToken = accessor.getFirstNativeHeader("access_token");
        return StringUtils.hasText(accessToken) ? accessToken.trim() : null;
    }
}
