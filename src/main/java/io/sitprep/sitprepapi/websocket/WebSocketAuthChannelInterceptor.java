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

@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.CONNECT) {
            return message;
        }

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
            return message;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid WebSocket auth token", e);
        }
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
